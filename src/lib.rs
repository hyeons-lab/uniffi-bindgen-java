//! Java + Kotlin bindings generator for [UniFFI](https://github.com/mozilla/uniffi-rs).
//!
//! Two backends share one CLI and one FFI runtime:
//!
//! - The Java backend (default; `--language java` or no flag) emits
//!   `.java` source using `CompletableFuture` for async, optional
//!   JSpecify nullness annotations, and no external runtime dependency.
//! - The Kotlin backend (`--language kotlin`) emits idiomatic Kotlin —
//!   `suspend fun` for async (requires `kotlinx.coroutines` at runtime
//!   when the interface contains async APIs), nullable types as `T?`,
//!   `data class` records, `sealed class` errors, `companion object`
//!   factories.
//!
//! Both backends generate against Java's Foreign Function & Memory API
//! (Project Panama) rather than JNA. The wire format is shared, so a
//! single compiled cdylib serves consumers of either backend.
//!
//! The library entry point is [`generate`], driven by [`GenerateOptions`]
//! (set [`Language::Kotlin`] on the options struct to switch backends).
//! [`run_main`] is the CLI front end; the `uniffi-bindgen-java` binary
//! calls it directly.

use anyhow::{Context, Result};
use camino::{Utf8Path, Utf8PathBuf};
use clap::{Parser, Subcommand};
use once_cell::sync::Lazy;
use regex::Regex;
use std::collections::HashMap;
use std::fs;
use uniffi_bindgen::{
    BindgenLoader, BindgenPaths, Component, ComponentInterface, interface::rename,
};

/// Shared splitter regex. Matches `// UNIFFI:FILE <name>` with an optional
/// trailing `\r` so Windows checkouts with CRLF line endings still parse.
/// Compiled once via `Lazy` rather than re-created on every call to
/// `split_and_write`.
static FILE_MARKER: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"(?m)^// UNIFFI:FILE (\S+)\r?\n").unwrap());

mod gen_java;
mod gen_kotlin;
mod gen_lang;

/// Target output language. Added in P2; Java is the default for
/// backwards compatibility with pre-P2 callers of the library API.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Language {
    #[default]
    Java,
    Kotlin,
}

/// Options for generating bindings.
///
/// `#[non_exhaustive]` + the `new(..)` constructor below mean new fields can
/// be added in future releases without breaking downstream callers. Prefer
/// `GenerateOptions::new(source, out_dir)` over struct literals; mutate
/// individual fields after construction to override defaults.
#[non_exhaustive]
pub struct GenerateOptions {
    /// Path to the source file (UDL or library)
    pub source: Utf8PathBuf,
    /// Directory to write generated files
    pub out_dir: Utf8PathBuf,
    /// Whether to format generated code (currently not implemented)
    pub format: bool,
    /// Optional crate filter - only generate bindings for this crate
    pub crate_filter: Option<String>,
    /// Output language. Defaults to Java.
    pub language: Language,
}

impl GenerateOptions {
    /// Construct `GenerateOptions` with defaults for everything except the
    /// source path and output directory (which never have a sensible
    /// default). Mutate individual fields to override:
    ///
    /// ```ignore
    /// let mut opts = GenerateOptions::new(cdylib_path, out_dir);
    /// opts.language = Language::Kotlin;
    /// opts.format = false;
    /// generate(&loader, &opts)?;
    /// ```
    pub fn new(source: Utf8PathBuf, out_dir: Utf8PathBuf) -> Self {
        Self {
            source,
            out_dir,
            format: true,
            crate_filter: None,
            language: Language::Java,
        }
    }
}

pub fn generate(loader: &BindgenLoader, options: &GenerateOptions) -> Result<()> {
    match options.language {
        Language::Java => generate_java(loader, options),
        Language::Kotlin => generate_kotlin(loader, options),
    }
}

/// Split a `generate_bindings()` output string on `// UNIFFI:FILE <name>`
/// markers and write each section as a separate file under `out_dir`. Used by
/// both backends — the marker format is language-neutral.
fn split_and_write(bindings_str: &str, out_dir: &Utf8Path) -> Result<()> {
    let markers: Vec<(String, usize, usize)> = FILE_MARKER
        .captures_iter(bindings_str)
        .map(|cap| {
            let whole = cap.get(0).unwrap();
            let filename = cap.get(1).unwrap().as_str().to_string();
            (filename, whole.start(), whole.end())
        })
        .collect();

    for (i, (filename, _, content_start)) in markers.iter().enumerate() {
        let content_end = markers
            .get(i + 1)
            .map(|(_, next_marker_start, _)| *next_marker_start)
            .unwrap_or(bindings_str.len());
        fs::write(
            out_dir.join(filename),
            &bindings_str[*content_start..content_end],
        )?;
    }
    Ok(())
}

fn generate_java(loader: &BindgenLoader, options: &GenerateOptions) -> Result<()> {
    let metadata = loader.load_metadata(&options.source)?;
    let cis = loader.load_cis(metadata)?;
    let cdylib = loader.library_name(&options.source).map(|l| l.to_string());
    let mut components =
        loader.load_components(cis, |ci, toml| parse_java_config(ci, toml, cdylib.clone()))?;

    apply_renames_and_external_packages_java(&mut components);
    for c in components.iter_mut() {
        c.ci.derive_ffi_funcs()?;
    }

    for Component { ci, config, .. } in components {
        if let Some(crate_filter) = &options.crate_filter
            && ci.crate_name() != crate_filter
        {
            continue;
        }

        let bindings_str = gen_java::generate_bindings(&config, &ci)?;
        let package_out_dir = options.out_dir.join(
            config
                .package_name()
                .split('.')
                .collect::<Vec<_>>()
                .join("/"),
        );
        fs::create_dir_all(&package_out_dir)?;
        split_and_write(&bindings_str, &package_out_dir)?;

        if config.nullness_annotations() {
            let package_line = format!("package {};", config.package_name());
            let package_info = format!("@org.jspecify.annotations.NullMarked\n{}", package_line);
            fs::write(package_out_dir.join("package-info.java"), package_info)?;
        }

        if options.format {
            // TODO: if there's a CLI formatter that makes sense to use here, use it, PRs welcome
            // seems like palantir-java-format is popular, but it's only exposed through plugins
            // google-java-format is legacy popular and does have an executable all-deps JAR, but
            // must be called with the full jar path including version numbers
            // prettier sorta works but requires npm and packages be around for a java generator
        }
    }
    Ok(())
}

fn generate_kotlin(loader: &BindgenLoader, options: &GenerateOptions) -> Result<()> {
    let metadata = loader.load_metadata(&options.source)?;
    let cis = loader.load_cis(metadata)?;
    let cdylib = loader.library_name(&options.source).map(|l| l.to_string());
    let mut components = loader.load_components(cis, |ci, toml| {
        parse_kotlin_config(ci, toml, cdylib.clone())
    })?;

    apply_renames_and_external_packages_kotlin(&mut components);
    for c in components.iter_mut() {
        c.ci.derive_ffi_funcs()?;
    }

    for Component { ci, config, .. } in components {
        if let Some(crate_filter) = &options.crate_filter
            && ci.crate_name() != crate_filter
        {
            continue;
        }

        let bindings_str = gen_kotlin::generate_bindings(&config, &ci)?;
        let bindings_str = inject_external_imports_kotlin(&bindings_str, &config);
        let package_out_dir = options.out_dir.join(
            config
                .package_name()
                .split('.')
                .collect::<Vec<_>>()
                .join("/"),
        );
        fs::create_dir_all(&package_out_dir)?;
        split_and_write(&bindings_str, &package_out_dir)?;
    }
    Ok(())
}

/// Inject `import <other-package>.*` lines after every `package <pkg>` line
/// in the rendered Kotlin output, so each generated file pulls in every
/// other crate's package in this multi-crate fixture. Without this, types
/// defined in (e.g.) `uniffi.uniffi_one_ns` are unresolved when referenced
/// from `uniffi.imported_types_sublib`.
///
/// `config.external_packages` is populated by
/// `apply_renames_and_external_packages_kotlin` to contain `crate -> package`
/// mappings for every OTHER component in the build (the current crate is
/// always absent), so we just emit `import {value}.*` for each entry.
///
/// Mirrors the parallel behaviour of upstream Kotlin bindings, which use
/// fully-qualified type names to achieve the same end. Wildcard imports
/// stay terser at the cost of some scope pollution that's harmless inside
/// generated code.
fn inject_external_imports_kotlin(bindings_str: &str, config: &gen_kotlin::Config) -> String {
    if config.external_packages.is_empty() {
        return bindings_str.to_string();
    }
    let mut imports: Vec<String> = config
        .external_packages
        .values()
        .map(|pkg| format!("import {pkg}.*"))
        .collect();
    imports.sort();
    imports.dedup();
    let import_block = imports.join("\n");

    let mut out = String::with_capacity(bindings_str.len() + import_block.len() * 16);
    for line in bindings_str.split_inclusive('\n') {
        out.push_str(line);
        let trimmed = line.trim_end_matches(|c| c == '\r' || c == '\n');
        if trimmed.starts_with("package ") {
            out.push_str(&import_block);
            out.push('\n');
        }
    }
    out
}

/// Parse Java configuration from TOML
fn parse_java_config(
    ci: &ComponentInterface,
    root_toml: toml::Value,
    cdylib: Option<String>,
) -> Result<gen_java::Config> {
    let mut config: gen_java::Config = match root_toml.get("bindings").and_then(|b| b.get("java")) {
        Some(v) => v.clone().try_into()?,
        None => Default::default(),
    };
    config
        .package_name
        .get_or_insert_with(|| format!("uniffi.{}", ci.namespace()));
    config.cdylib_name.get_or_insert_with(|| {
        cdylib
            .clone()
            .unwrap_or_else(|| format!("uniffi_{}", ci.namespace()))
    });
    Ok(config)
}

/// Parse Kotlin configuration from TOML
fn parse_kotlin_config(
    ci: &ComponentInterface,
    root_toml: toml::Value,
    cdylib: Option<String>,
) -> Result<gen_kotlin::Config> {
    let mut config: gen_kotlin::Config =
        match root_toml.get("bindings").and_then(|b| b.get("kotlin")) {
            Some(v) => v.clone().try_into()?,
            None => Default::default(),
        };
    config
        .package_name
        .get_or_insert_with(|| format!("uniffi.{}", ci.namespace()));
    config.cdylib_name.get_or_insert_with(|| {
        cdylib
            .clone()
            .unwrap_or_else(|| format!("uniffi_{}", ci.namespace()))
    });
    Ok(config)
}

/// Apply rename configurations and update external package mappings across all
/// Java components. This must be called before derive_ffi_funcs() since
/// renames affect FFI function names.
fn apply_renames_and_external_packages_java(components: &mut Vec<Component<gen_java::Config>>) {
    let mut module_renames = HashMap::new();
    for c in components.iter() {
        if !c.config.rename.is_empty() {
            let module_path = c.ci.crate_name().to_string();
            module_renames.insert(module_path, c.config.rename.clone());
        }
    }

    if !module_renames.is_empty() {
        for c in &mut *components {
            rename(&mut c.ci, &module_renames);
        }
    }

    let packages = HashMap::<String, String>::from_iter(
        components
            .iter()
            .map(|c| (c.ci.crate_name().to_string(), c.config.package_name())),
    );
    for c in components {
        for (ext_crate, ext_package) in &packages {
            if ext_crate != c.ci.crate_name() && !c.config.external_packages.contains_key(ext_crate)
            {
                c.config
                    .external_packages
                    .insert(ext_crate.to_string(), ext_package.clone());
            }
        }
    }
}

/// Kotlin-side mirror of the Java rename/external-package pass. Structurally
/// identical — the duplication is tolerated because `Component<C>` is
/// generic over C and the shared post-processing would need a Config trait
/// (wait for P3+ when that trait pays for itself).
fn apply_renames_and_external_packages_kotlin(components: &mut Vec<Component<gen_kotlin::Config>>) {
    let mut module_renames = HashMap::new();
    for c in components.iter() {
        if !c.config.rename.is_empty() {
            let module_path = c.ci.crate_name().to_string();
            module_renames.insert(module_path, c.config.rename.clone());
        }
    }

    if !module_renames.is_empty() {
        for c in &mut *components {
            rename(&mut c.ci, &module_renames);
        }
    }

    let packages = HashMap::<String, String>::from_iter(
        components
            .iter()
            .map(|c| (c.ci.crate_name().to_string(), c.config.package_name())),
    );
    for c in components {
        for (ext_crate, ext_package) in &packages {
            if ext_crate != c.ci.crate_name() && !c.config.external_packages.contains_key(ext_crate)
            {
                c.config
                    .external_packages
                    .insert(ext_crate.to_string(), ext_package.clone());
            }
        }
    }
}

/// Create BindgenPaths with cargo metadata layer and optional config override
fn create_bindgen_paths(
    config_override: Option<&Utf8Path>,
    metadata_no_deps: bool,
) -> Result<BindgenPaths> {
    let mut paths = BindgenPaths::default();

    // Add config override layer first (takes precedence)
    if let Some(config_path) = config_override {
        paths.add_config_override_layer(config_path.to_path_buf());
    }

    // Add cargo metadata layer for finding crate configs
    paths
        .add_cargo_metadata_layer(metadata_no_deps)
        .context("Failed to load cargo metadata")?;

    Ok(paths)
}

#[derive(Debug, Clone, Copy, Default, clap::ValueEnum)]
enum CliLanguage {
    #[default]
    Java,
    Kotlin,
}

impl From<CliLanguage> for Language {
    fn from(l: CliLanguage) -> Self {
        match l {
            CliLanguage::Java => Language::Java,
            CliLanguage::Kotlin => Language::Kotlin,
        }
    }
}

#[derive(Parser)]
#[clap(name = "uniffi-bindgen-java")]
#[clap(version = clap::crate_version!())]
#[clap(propagate_version = true, disable_help_subcommand = true)]
/// Java and Kotlin scaffolding and bindings generator for Rust
struct Cli {
    #[clap(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Generate bindings (Java by default; pass `--language kotlin` for Kotlin).
    Generate {
        /// Target output language. Defaults to Java.
        #[clap(long, value_enum, default_value_t = CliLanguage::Java)]
        language: CliLanguage,

        /// Directory in which to write generated files. Default is same folder as .udl file.
        #[clap(long, short)]
        out_dir: Option<Utf8PathBuf>,

        /// Do not try to format the generated bindings.
        #[clap(long, short)]
        no_format: bool,

        /// Path to optional uniffi config file. This config is merged with the `uniffi.toml` config present in each crate, with its values taking precedence.
        #[clap(long, short)]
        config: Option<Utf8PathBuf>,

        /// When a library is passed as SOURCE, only generate bindings for this crate.
        /// When a UDL file is passed, use this as the crate name instead of attempting to
        /// locate and parse Cargo.toml.
        #[clap(long = "crate")]
        crate_name: Option<String>,

        /// Path to the UDL file or compiled library (.so, .dll, .dylib, or .a)
        source: Utf8PathBuf,

        /// Whether we should exclude dependencies when running "cargo metadata".
        /// This will mean external types may not be resolved if they are implemented in crates
        /// outside of this workspace.
        /// This can be used in environments when all types are in the namespace and fetching
        /// all sub-dependencies causes obscure platform specific problems.
        #[clap(long)]
        metadata_no_deps: bool,
    },
    /// Generate Rust scaffolding code
    Scaffolding {
        /// Directory in which to write generated files. Default is same folder as .udl file.
        #[clap(long, short)]
        out_dir: Option<Utf8PathBuf>,

        /// Do not try to format the generated bindings.
        #[clap(long, short)]
        no_format: bool,

        /// Path to the UDL file.
        udl_file: Utf8PathBuf,
    },
    /// Print a debug representation of the interface from a dynamic library
    PrintRepr {
        /// Path to the library file (.so, .dll, .dylib, or .a)
        path: Utf8PathBuf,
    },
}

pub fn run_main() -> Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Commands::Generate {
            language,
            out_dir,
            no_format,
            config,
            crate_name,
            source,
            metadata_no_deps,
        } => {
            let out_dir = out_dir.unwrap_or_else(|| {
                source
                    .parent()
                    .map(|p| p.to_path_buf())
                    .unwrap_or_else(|| Utf8PathBuf::from("."))
            });

            // Create BindgenPaths with cargo metadata and optional config override
            let paths = create_bindgen_paths(config.as_deref(), metadata_no_deps)?;
            let loader = BindgenLoader::new(paths);

            fs::create_dir_all(&out_dir)?;

            generate(
                &loader,
                &GenerateOptions {
                    source,
                    out_dir,
                    format: !no_format,
                    crate_filter: crate_name,
                    language: language.into(),
                },
            )?;
        }
        Commands::Scaffolding {
            out_dir,
            no_format,
            udl_file,
        } => {
            uniffi_bindgen::generate_component_scaffolding(
                &udl_file,
                out_dir.as_deref(),
                !no_format,
            )?;
        }
        Commands::PrintRepr { path } => {
            uniffi_bindgen::print_repr(&path)?;
        }
    };
    Ok(())
}
