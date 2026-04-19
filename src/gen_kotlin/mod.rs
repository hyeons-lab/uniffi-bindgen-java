/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Kotlin backend. P2 wired up the CLI dispatch and emitted a hand-written
//! namespace stub; P3a upgrades that to Askama template rendering so
//! subsequent phases can add real type converters, the FFM runtime, etc.
//! without rewriting the pipeline.
//!
//! Still bounded for P3a: the `wrapper.kt` template emits the same P2
//! skeleton (an `object <Namespace>`). P3b introduces the FFM runtime +
//! primitive FfiConverters; P3c records; P3d flat enums; P3e reserved-
//! word handling.

use std::borrow::Borrow;
use std::collections::HashMap;

use anyhow::{Context, Result};
use askama::Template;
use heck::ToLowerCamelCase;
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use uniffi_bindgen::{ComponentInterface, interface::FfiType};

pub use crate::gen_lang::CustomTypeConfig;
use crate::gen_lang::ExternalPackageResolver;

/// Kotlin reserved words that need backtick-escaping or rename when used as
/// identifiers. Includes hard keywords (always reserved) and modifiers /
/// soft keywords that conflict with common UniFFI types.
static KOTLIN_KEYWORDS: Lazy<HashMap<&'static str, ()>> = Lazy::new(|| {
    [
        // Hard keywords
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
        // Soft keywords / modifiers that commonly clash with identifiers
        "abstract",
        "actual",
        "annotation",
        "by",
        "catch",
        "companion",
        "const",
        "constructor",
        "crossinline",
        "data",
        "delegate",
        "dynamic",
        "enum",
        "expect",
        "external",
        "field",
        "file",
        "final",
        "finally",
        "get",
        "import",
        "infix",
        "init",
        "inline",
        "inner",
        "internal",
        "lateinit",
        "noinline",
        "open",
        "operator",
        "out",
        "override",
        "param",
        "private",
        "property",
        "protected",
        "public",
        "receiver",
        "reified",
        "sealed",
        "set",
        "setparam",
        "suspend",
        "tailrec",
        "value",
        "vararg",
        "where",
    ]
    .into_iter()
    .map(|k| (k, ()))
    .collect()
});

fn fixup_keyword(name: String) -> String {
    if KOTLIN_KEYWORDS.contains_key(name.as_str()) {
        format!("`{name}`")
    } else {
        name
    }
}

/// Per-language oracle that knows how to render Kotlin-specific identifiers
/// and FFI types. Mirrors `gen_java::JavaCodeOracle`'s per-type methods but
/// emits Kotlin syntax where it differs (e.g. capitalized primitive type
/// names, `as` casts instead of `(Type)`, backtick-escaped reserved words).
#[derive(Debug, Default, Clone, Copy)]
pub struct KotlinCodeOracle;

impl KotlinCodeOracle {
    pub fn var_name(&self, nm: &str) -> String {
        fixup_keyword(self.var_name_raw(nm))
    }

    pub fn var_name_raw(&self, nm: &str) -> String {
        nm.to_lower_camel_case()
    }

    // Used by the `fn_name` filter; the filter itself isn't yet referenced
    // from any template (P3e will hook it into the namespace function
    // rendering). Keep the implementation in place to avoid churn.
    #[allow(dead_code)]
    pub fn fn_name(&self, nm: &str) -> String {
        fixup_keyword(nm.to_lower_camel_case())
    }

    /// FFI type label for use in Kotlin method signatures + MethodHandle
    /// wrapper functions. Kotlin primitives are capitalized (`Long`, `Int`,
    /// `Byte`, etc.); pointer-shaped FFI types stay as
    /// `java.lang.foreign.MemorySegment`.
    pub fn ffi_type_label(&self, ffi_type: &FfiType) -> String {
        match ffi_type {
            FfiType::Int8 | FfiType::UInt8 => "Byte".to_string(),
            FfiType::Int16 | FfiType::UInt16 => "Short".to_string(),
            FfiType::Int32 | FfiType::UInt32 => "Int".to_string(),
            FfiType::Int64 | FfiType::UInt64 => "Long".to_string(),
            FfiType::Float32 => "Float".to_string(),
            FfiType::Float64 => "Double".to_string(),
            FfiType::Handle => "Long".to_string(),
            FfiType::RustBuffer(_)
            | FfiType::RustCallStatus
            | FfiType::ForeignBytes
            | FfiType::Callback(_)
            | FfiType::Struct(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => "java.lang.foreign.MemorySegment".to_string(),
        }
    }

    /// `ValueLayout` constant for FFM `FunctionDescriptor` construction.
    /// Identical to the Java backend — FFM's API is the same from Kotlin.
    pub fn ffi_value_layout(&self, ffi_type: &FfiType) -> String {
        match ffi_type {
            FfiType::Int8 | FfiType::UInt8 => "java.lang.foreign.ValueLayout.JAVA_BYTE".to_string(),
            FfiType::Int16 | FfiType::UInt16 => {
                "java.lang.foreign.ValueLayout.JAVA_SHORT".to_string()
            }
            FfiType::Int32 | FfiType::UInt32 => {
                "java.lang.foreign.ValueLayout.JAVA_INT".to_string()
            }
            FfiType::Int64 | FfiType::UInt64 | FfiType::Handle => {
                "java.lang.foreign.ValueLayout.JAVA_LONG".to_string()
            }
            FfiType::Float32 => "java.lang.foreign.ValueLayout.JAVA_FLOAT".to_string(),
            FfiType::Float64 => "java.lang.foreign.ValueLayout.JAVA_DOUBLE".to_string(),
            FfiType::RustBuffer(_) => "RustBuffer.LAYOUT".to_string(),
            FfiType::RustCallStatus => "java.lang.foreign.ValueLayout.ADDRESS".to_string(),
            FfiType::ForeignBytes => "ForeignBytes.LAYOUT".to_string(),
            // The Java backend emits the matching `Uniffi<Name>` struct
            // class (alongside its LAYOUT) via NamespaceLibraryTemplate's
            // `FfiDefinition::Struct` branch. The Kotlin templates haven't
            // ported that branch yet, so emitting the layout reference
            // here would produce kotlinc errors at build time. Fail loud
            // at codegen so any callback / struct-using fixture surfaces
            // the gap immediately. Arithmetic doesn't trigger this; the
            // first fixture that does is coverall.
            FfiType::Struct(name) => panic!(
                "Kotlin FFI struct layouts are not generated yet; \
                 cannot bind FFI struct `{}`. See gen_kotlin/mod.rs.",
                name
            ),
            FfiType::Callback(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => "java.lang.foreign.ValueLayout.ADDRESS".to_string(),
        }
    }

    /// Cast expression suffix for `MethodHandle.invokeExact()` return values
    /// in Kotlin. Kotlin uses `as Long`/`as Int`/etc. rather than Java's
    /// `(long) <expr>` C-style cast.
    pub fn ffi_invoke_exact_cast(&self, ffi_type: &FfiType) -> String {
        match ffi_type {
            FfiType::Int8 | FfiType::UInt8 => " as Byte".to_string(),
            FfiType::Int16 | FfiType::UInt16 => " as Short".to_string(),
            FfiType::Int32 | FfiType::UInt32 => " as Int".to_string(),
            FfiType::Int64 | FfiType::UInt64 | FfiType::Handle => " as Long".to_string(),
            FfiType::Float32 => " as Float".to_string(),
            FfiType::Float64 => " as Double".to_string(),
            FfiType::RustBuffer(_)
            | FfiType::RustCallStatus
            | FfiType::ForeignBytes
            | FfiType::Callback(_)
            | FfiType::Struct(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => " as java.lang.foreign.MemorySegment".to_string(),
        }
    }

    /// Whether the FFI return type is a struct that needs a
    /// `SegmentAllocator` as the first downcall argument.
    pub fn ffi_type_is_struct(&self, ffi_type: &FfiType) -> bool {
        matches!(
            ffi_type,
            FfiType::RustBuffer(_) | FfiType::ForeignBytes | FfiType::Struct(_)
        )
    }
}

// `UpperCamelCaseKotlin` trait removed alongside the `FfiType::Struct`
// codegen branch — that branch now panics until the Kotlin templates
// emit the matching struct definitions, so the casing helper has no
// remaining caller. Reintroduce when the struct emission lands (likely
// the same phase that ports `FfiDefinition::Struct` from the Java
// `NamespaceLibraryTemplate`).

/// Kotlin-specific config. Field set mirrors the language-neutral subset of
/// `gen_java::Config` plus Kotlin-only knobs (none yet in P2; `android`
/// plumbed through so P4+ cleaner code can pick it up without another
/// refactor).
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub(super) package_name: Option<String>,
    pub(super) cdylib_name: Option<String>,
    #[serde(default)]
    custom_types: HashMap<String, CustomTypeConfig>,
    #[serde(default)]
    pub(super) external_packages: HashMap<String, String>,
    #[serde(default)]
    android: bool,
    #[serde(default)]
    pub(super) rename: toml::Table,
    #[serde(default)]
    omit_checksums: bool,
}

// `cdylib_name`, `omit_checksums`, and `android` are defined now so the
// TOML schema matches the Java backend and later phases don't churn
// `gen_kotlin::Config` for every new consumer. `#[allow(dead_code)]` until
// subsequent phases hook them up.
impl Config {
    pub fn package_name(&self) -> String {
        self.package_name.clone().unwrap_or_else(|| "uniffi".into())
    }

    #[allow(dead_code)]
    pub fn cdylib_name(&self) -> String {
        self.cdylib_name.clone().unwrap_or_else(|| "uniffi".into())
    }

    #[allow(dead_code)]
    pub fn omit_checksums(&self) -> bool {
        self.omit_checksums
    }

    /// Whether to generate PanamaPort imports for Android compatibility.
    /// Mirrors the `android` flag in the Java config. Later phases consume
    /// this in the cleaner-helper template.
    #[allow(dead_code)]
    pub fn android(&self) -> bool {
        self.android
    }
}

impl ExternalPackageResolver for Config {
    fn external_type_package_name(&self, module_path: &str, namespace: &str) -> String {
        let crate_name = module_path.split("::").next().unwrap();
        match self.external_packages.get(crate_name) {
            Some(name) => name.clone(),
            None => format!("uniffi.{namespace}"),
        }
    }
}

/// Askama-rendered root template. Mirrors `gen_java::JavaWrapper` at the
/// structural level but points at Kotlin templates (`syntax = "kotlin"`,
/// `path = "wrapper.kt"`). The template body is intentionally thin for
/// P3a — it renders the same namespace-object skeleton as the P2
/// hand-written output. P3b+ adds `{% include %}` directives for the
/// FFM runtime + per-type converter templates.
#[derive(Template)]
#[template(syntax = "kotlin", escape = "none", path = "wrapper.kt")]
pub struct KotlinWrapper<'a> {
    config: Config,
    ci: &'a ComponentInterface,
}

impl<'a> KotlinWrapper<'a> {
    pub fn new(config: Config, ci: &'a ComponentInterface) -> Self {
        Self { config, ci }
    }

    /// Kotlin namespace-object name. Uppercases the first character only,
    /// leaving the rest untouched (e.g. `primitive_arrays` →
    /// `Primitive_arrays`). Mirrors the behavior inherited from the
    /// pre-Askama Kotlin entrypoint; a more Kotlin-idiomatic camel-case
    /// conversion can be introduced later with a visible snapshot diff.
    pub fn namespace_class_name(&self) -> String {
        let mut chars = self.ci.namespace().chars();
        match chars.next() {
            Some(c) => c.to_uppercase().chain(chars).collect(),
            None => String::new(),
        }
    }
}

/// Generate Kotlin bindings as a single string. Split by `split_and_write`
/// into individual `.kt` files via the `// UNIFFI:FILE` markers emitted by
/// the templates.
pub fn generate_bindings(config: &Config, ci: &ComponentInterface) -> Result<String> {
    KotlinWrapper::new(config.clone(), ci)
        .render()
        .context("failed to render Kotlin bindings")
}

// Filters exposed to Askama templates. Askama discovers them via the
// `filters::<name>` path on the template's owning module, so they live
// here rather than in a separate file. Mirror the subset of `gen_java`
// filters needed by the Kotlin runtime + namespace-function rendering.
pub mod filters {
    use askama::Values;
    use uniffi_bindgen::interface::FfiType;

    use super::KotlinCodeOracle;

    /// Kotlin-idiomatic variable name (lowerCamelCase, backtick-escaped if reserved).
    pub fn var_name<S: AsRef<str>>(nm: S, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.var_name(nm.as_ref()))
    }

    /// Kotlin-idiomatic function name. Same casing rules as `var_name` for now.
    /// Defined ahead of its first template reference so P3e doesn't have to
    /// reshuffle the filter module when it adds the namespace function loop.
    #[allow(dead_code)]
    pub fn fn_name<S: AsRef<str>>(nm: S, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.fn_name(nm.as_ref()))
    }

    /// FFI type name (Kotlin primitive for scalars, `MemorySegment` for everything else).
    pub fn ffi_type_name(type_: &FfiType, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_type_label(type_))
    }

    /// `ValueLayout` constant for `FunctionDescriptor` construction.
    pub fn ffi_value_layout(type_: &FfiType, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_value_layout(type_))
    }

    /// Cast suffix (`" as Long"`, `" as java.lang.foreign.MemorySegment"`, …)
    /// for `MethodHandle.invokeExact()` return values in Kotlin. Includes the
    /// leading space so the template can append it directly to the call site.
    pub fn ffi_invoke_exact_cast(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_invoke_exact_cast(type_))
    }

    /// Whether the FFI type is a struct that needs a `SegmentAllocator`
    /// as the first argument to `invokeExact()`.
    pub fn ffi_type_is_struct(type_: &FfiType, _v: &dyn Values) -> Result<bool, askama::Error> {
        Ok(KotlinCodeOracle.ffi_type_is_struct(type_))
    }
}
