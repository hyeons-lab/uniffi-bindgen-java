use anyhow::{Context, Result};
use camino::{Utf8Path, Utf8PathBuf};
use clap::{Parser, Subcommand};
use std::collections::HashMap;
use std::fs;
use uniffi_bindgen::{
    BindgenLoader, BindgenPaths, Component, ComponentInterface, interface::rename,
};

mod gen_java;
use gen_java::Config;

/// Options for generating Java bindings
pub struct GenerateOptions {
    /// Path to the source file (UDL or library)
    pub source: Utf8PathBuf,
    /// Directory to write generated files
    pub out_dir: Utf8PathBuf,
    /// Whether to format generated code (currently not implemented)
    pub format: bool,
    /// Optional crate filter - only generate bindings for this crate
    pub crate_filter: Option<String>,
}

pub fn generate(loader: &BindgenLoader, options: &GenerateOptions) -> Result<()> {
    let metadata = loader.load_metadata(&options.source)?;
    let cis = loader.load_cis(metadata)?;
    let cdylib = loader.library_name(&options.source).map(|l| l.to_string());
    let mut components =
        loader.load_components(cis, |ci, toml| parse_config(ci, toml, cdylib.clone()))?;

    // Apply renames and update external package mappings (must happen before derive_ffi_funcs)
    apply_renames_and_external_packages(&mut components);

    // Derive FFI functions for each component (after renames)
    for c in components.iter_mut() {
        c.ci.derive_ffi_funcs()?;
    }

    // Generate and write bindings for each component.
    //
    // File splitting is driven by `// UNIFFI:FILE <name>` markers emitted by
    // the templates. Each marker starts a new output file; content between
    // markers (with the marker line stripped) is written verbatim. Anything
    // before the first marker is template preamble (root-template comments,
    // macro imports) that doesn't belong in any single output file and is
    // discarded.
    //
    // Marker-based splitting replaces a regex over Java top-level decl
    // keywords. The regex was fragile (every new modifier or keyword broke
    // it) and unsuitable for the planned Kotlin backend, which has many
    // more top-level forms (`data class`, `sealed interface`, `data object`,
    // `fun interface`, etc.). Markers also let templates name their own
    // output files explicitly, decoupling file naming from emitted syntax.
    let marker_re = regex::Regex::new(r"(?m)^// UNIFFI:FILE (\S+)\n").unwrap();

    for Component { ci, config, .. } in components {
        if let Some(crate_filter) = &options.crate_filter
            && ci.crate_name() != crate_filter
        {
            continue;
        }

        let bindings_str = gen_java::generate_bindings(&config, &ci)?;
        let java_package_out_dir = options.out_dir.join(
            config
                .package_name()
                .split('.')
                .collect::<Vec<_>>()
                .join("/"),
        );
        fs::create_dir_all(&java_package_out_dir)?;

        let markers: Vec<(String, usize, usize)> = marker_re
            .captures_iter(&bindings_str)
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
            let content = &bindings_str[*content_start..content_end];
            fs::write(java_package_out_dir.join(filename), content)?;
        }

        if config.nullness_annotations() {
            let package_line = format!("package {};", config.package_name());
            let package_info = format!("@org.jspecify.annotations.NullMarked\n{}", package_line);
            fs::write(java_package_out_dir.join("package-info.java"), package_info)?;
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

/// Parse Java configuration from TOML
fn parse_config(
    ci: &ComponentInterface,
    root_toml: toml::Value,
    cdylib: Option<String>,
) -> Result<Config> {
    let mut config: Config = match root_toml.get("bindings").and_then(|b| b.get("java")) {
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

/// Apply rename configurations and update external package mappings across all components.
/// This must be called before derive_ffi_funcs() since renames affect FFI function names.
fn apply_renames_and_external_packages(components: &mut Vec<Component<Config>>) {
    // Collect all rename configurations from all components, keyed by module_path (crate name)
    let mut module_renames = HashMap::new();
    for c in components.iter() {
        if !c.config.rename.is_empty() {
            let module_path = c.ci.crate_name().to_string();
            module_renames.insert(module_path, c.config.rename.clone());
        }
    }

    // Apply rename configurations to all components
    if !module_renames.is_empty() {
        for c in &mut *components {
            rename(&mut c.ci, &module_renames);
        }
    }

    // Update external package mappings
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

#[derive(Parser)]
#[clap(name = "uniffi-bindgen-java")]
#[clap(version = clap::crate_version!())]
#[clap(propagate_version = true, disable_help_subcommand = true)]
/// Java scaffolding and bindings generator for Rust
struct Cli {
    #[clap(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Generate Java bindings
    Generate {
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
