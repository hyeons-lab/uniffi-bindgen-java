/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Kotlin backend. P3e wires up user-facing namespace function rendering:
//! `func_decl` macro + `lift_fn`/`lower_fn`/`type_name`/
//! `primitive_call_suffix`/`has_primitive_ffi_type` filters, plus a
//! `CodeType` / `AsCodeType` trait pair for primitives + String + bytes.
//! Result: `cargo run -- generate --language kotlin` against the
//! arithmetic fixture emits an `Arithmetical.kt` whose `add`/`sub`/`div`/
//! `equal` are callable from Kotlin user code.
//!
//! Still bounded: only primitive + `String`/`ByteArray` types route
//! through `AsCodeType`; non-primitive types (records, enums, objects,
//! callbacks, optionals, sequences, maps, custom) panic at codegen time
//! with a clear error message — by design, matching the P3d
//! `FfiType::Struct` panic. Subsequent phases (P3f records, P3g
//! enums+errors, …) will replace those panics with real `CodeType`
//! impls.

use std::borrow::Borrow;
use std::collections::HashMap;
use std::fmt::Debug;

use anyhow::{Context, Result};
use askama::Template;
use heck::{ToLowerCamelCase, ToShoutySnakeCase, ToUpperCamelCase};
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use uniffi_bindgen::{
    ComponentInterface,
    interface::{Argument, FfiType, Field},
};
use uniffi_meta::{AsType, Type};

pub use crate::gen_lang::CustomTypeConfig;
use crate::gen_lang::ExternalPackageResolver;

mod compounds;
mod enum_;
mod primitives;
mod record;

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

    pub fn fn_name(&self, nm: &str) -> String {
        fixup_keyword(nm.to_lower_camel_case())
    }

    /// Kotlin class / data class / object name. UpperCamelCase + reserved-word
    /// fixup. For types marked as errors (`[Error] enum MyError { ... }`),
    /// rewrite `*Error` → `*Exception` so the Kotlin surface reads as a
    /// `kotlin.Exception` subclass. Mirrors `JavaCodeOracle::class_name`.
    pub fn class_name(&self, ci: &ComponentInterface, nm: &str) -> String {
        let name = nm.to_string().to_upper_camel_case();
        fixup_keyword(if ci.is_name_used_as_error(nm) {
            self.convert_error_suffix(&name)
        } else {
            name
        })
    }

    /// `FooError` → `FooException`. `Error` alone → `Error` (leaves the
    /// bare suffix untouched; matches the Java backend).
    fn convert_error_suffix(&self, nm: &str) -> String {
        match nm.strip_suffix("Error") {
            Some(stripped) if !stripped.is_empty() => format!("{stripped}Exception"),
            _ => nm.to_string(),
        }
    }

    /// Kotlin enum entry name — SCREAMING_SNAKE_CASE, backtick-escaped if
    /// a reserved word. Mirrors `JavaCodeOracle::enum_variant_name` modulo
    /// the reserved-word fixup (Kotlin requires it for identifiers that
    /// collide with hard keywords even in enum-entry position).
    pub fn enum_variant_name(&self, nm: &str) -> String {
        fixup_keyword(nm.to_string().to_shouty_snake_case())
    }

    /// Error-variant nested-class name. UpperCamelCase'd, then routed
    /// through `convert_error_suffix` — so a trailing `Error` becomes
    /// `Exception`, and any other name passes through unchanged. The
    /// parent `sealed class` already carries the `Exception` suffix;
    /// variants don't get it added automatically.
    ///
    /// Examples: `FooError` → `FooException`, `IntegerOverflow` →
    /// `IntegerOverflow` (arithmetic's typed error nests as
    /// `ArithmeticException.IntegerOverflow`, not `...Exception`).
    pub fn error_variant_name(&self, nm: &str) -> String {
        fixup_keyword(self.convert_error_suffix(&nm.to_string().to_upper_camel_case()))
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

/// Per-type code generation hooks. Mirrors `gen_java::CodeType`.
///
/// Implementors live in `gen_kotlin::primitives` (and, in later phases,
/// per-category modules for records/enums/objects). The trait is kept
/// inside `gen_kotlin` rather than hoisted to `gen_lang/` because the
/// FFI-converter naming convention currently differs between backends —
/// Java emits `FfiConverterX.INSTANCE.lift(...)` (enum-singleton pattern),
/// while Kotlin emits `FfiConverterX.lift(...)` (object-singleton).
/// Hoisting now would force one backend or the other to grow a wrapper.
trait CodeType: Debug {
    /// The language-specific label used to reference this type — appears
    /// in method signatures, property declarations, etc.
    fn type_label(&self, ci: &ComponentInterface, config: &Config) -> String;

    /// The Kotlin primitive name if this type maps directly to a JVM
    /// primitive (`Long`, `Int`, …). `None` for boxed / non-primitive
    /// types. Used by `primitive_call_suffix` and `has_primitive_ffi_type`
    /// to decide whether a function call can bypass `FfiConverter*` and
    /// hit the primitive-specialized `uniffiRustCall<Type>` helper
    /// directly.
    fn type_label_primitive(&self) -> Option<String> {
        None
    }

    /// A representation of the type label that can be used as part of
    /// another identifier — e.g. `FfiConverterLong`, `readFoo`.
    fn canonical_name(&self) -> String;

    /// `FfiConverter` object name. Kotlin converters are `object`s, so
    /// callers can address `lift` / `lower` / `read` / `write` directly
    /// (no `.INSTANCE` indirection like the Java backend).
    fn ffi_converter_name(&self) -> String {
        format!("FfiConverter{}", self.canonical_name())
    }
}

/// Bridge from any `Type`-like value (the high-level UDL type, an
/// argument, a record field, …) to the corresponding `CodeType` impl.
/// The `Type` arm is the source of truth; every other impl forwards.
trait AsCodeType {
    fn as_codetype(&self) -> Box<dyn CodeType>;
}

impl AsCodeType for Type {
    fn as_codetype(&self) -> Box<dyn CodeType> {
        match self.as_type() {
            Type::Boolean => Box::new(primitives::BooleanCodeType),
            Type::UInt8 | Type::Int8 => Box::new(primitives::Int8CodeType),
            Type::UInt16 | Type::Int16 => Box::new(primitives::Int16CodeType),
            Type::UInt32 | Type::Int32 => Box::new(primitives::Int32CodeType),
            Type::UInt64 | Type::Int64 => Box::new(primitives::Int64CodeType),
            Type::Float32 => Box::new(primitives::Float32CodeType),
            Type::Float64 => Box::new(primitives::Float64CodeType),
            Type::String => Box::new(primitives::StringCodeType),
            Type::Bytes => Box::new(primitives::BytesCodeType),

            Type::Record { name, .. } => Box::new(record::RecordCodeType::new(name)),
            Type::Enum { name, .. } => Box::new(enum_::EnumCodeType::new(name)),
            Type::Optional { inner_type } => {
                Box::new(compounds::OptionalCodeType::new(*inner_type))
            }
            Type::Sequence { inner_type } => match inner_type.as_ref() {
                Type::Int16 | Type::UInt16 => Box::new(compounds::Int16ArrayCodeType),
                Type::Int32 | Type::UInt32 => Box::new(compounds::Int32ArrayCodeType),
                Type::Int64 | Type::UInt64 => Box::new(compounds::Int64ArrayCodeType),
                Type::Float32 => Box::new(compounds::Float32ArrayCodeType),
                Type::Float64 => Box::new(compounds::Float64ArrayCodeType),
                Type::Boolean => Box::new(compounds::BooleanArrayCodeType),
                // Int8/UInt8 fall through to the generic path — byte-
                // array sequences come in via the separate
                // `Type::Bytes` → `ByteArray` route. Mirrors the Java
                // backend's shape.
                _ => Box::new(compounds::SequenceCodeType::new(*inner_type)),
            },
            Type::Map {
                key_type,
                value_type,
            } => Box::new(compounds::MapCodeType::new(*key_type, *value_type)),

            // Non-primitive types land in later phases. Panicking with a
            // clear message at codegen time matches the P3d
            // `FfiType::Struct` strategy: a fixture that exercises one of
            // these surfaces the gap loudly rather than producing
            // kotlinc-uncompilable output. Update each arm as the
            // corresponding template support lands.
            other => panic!(
                "Kotlin CodeType not implemented for `{:?}` yet \
                 (P3i+ adds objects, callbacks, custom). \
                 See gen_kotlin/mod.rs.",
                other
            ),
        }
    }
}

impl AsCodeType for &'_ Type {
    fn as_codetype(&self) -> Box<dyn CodeType> {
        (*self).as_codetype()
    }
}

// Askama auto-borrows template variables, so a `Some(return_type)` arm
// hands the macro a `&&Type` rather than a `&Type`. Without this impl
// the `Template` derive fails with "AsCodeType not implemented for &&Type".
impl AsCodeType for &&'_ Type {
    fn as_codetype(&self) -> Box<dyn CodeType> {
        (**self).as_codetype()
    }
}

impl AsCodeType for &'_ Argument {
    fn as_codetype(&self) -> Box<dyn CodeType> {
        self.as_type().as_codetype()
    }
}

impl AsCodeType for &'_ Field {
    fn as_codetype(&self) -> Box<dyn CodeType> {
        self.as_type().as_codetype()
    }
}

impl AsCodeType for &'_ Box<Type> {
    fn as_codetype(&self) -> Box<dyn CodeType> {
        self.as_type().as_codetype()
    }
}

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
/// `path = "wrapper.kt"`). The wrapper template `{% include %}`s the
/// runtime + primitive-converter templates and emits the namespace
/// `object` populated with `func_decl` macro calls.
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
mod filters {
    use askama::Values;
    use uniffi_bindgen::interface::{FfiType, Variant};
    use uniffi_meta::AsType;

    use super::{AsCodeType, Config, KotlinCodeOracle};
    use uniffi_bindgen::ComponentInterface;

    // Filter functions are `pub(super)` to match the visibility of the
    // private `AsCodeType` / `CodeType` traits they reference. Mirrors
    // the gen_java filter module's strategy.

    /// Kotlin-idiomatic variable name (lowerCamelCase, backtick-escaped if reserved).
    pub(super) fn var_name<S: AsRef<str>>(nm: S, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.var_name(nm.as_ref()))
    }

    /// Kotlin-idiomatic function name. Same casing rules as `var_name`.
    pub(super) fn fn_name<S: AsRef<str>>(nm: S, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.fn_name(nm.as_ref()))
    }

    /// Flat-enum variant name — SCREAMING_SNAKE_CASE, backtick-escaped if
    /// the upshouted form collides with a Kotlin reserved word.
    pub(super) fn variant_name(
        variant: &Variant,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.enum_variant_name(variant.name()))
    }

    /// Error-variant class name — UpperCamelCase plus the `*Error →
    /// *Exception` rewrite when the Rust variant happens to end in
    /// `Error`. Usually a plain UpperCamelCase since variant names rarely
    /// carry the suffix (the parent enum does).
    pub(super) fn error_variant_name(
        variant: &Variant,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.error_variant_name(variant.name()))
    }

    /// User-facing Kotlin type label — `Long`, `Boolean`, `String`,
    /// `ByteArray`, etc. Routes through `CodeType::type_label`, which
    /// dispatches by `Type` arm. Panics for non-primitive types until
    /// the corresponding `CodeType` impl lands (records in P3f, enums in
    /// P3g, …).
    pub(super) fn type_name(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
        ci: &ComponentInterface,
        config: &Config,
    ) -> Result<String, askama::Error> {
        Ok(as_ct.as_codetype().type_label(ci, config))
    }

    /// `FfiConverter<Name>.lift` expression. Used at call sites to
    /// convert FFI return values back to Kotlin types. Kotlin
    /// `FfiConverter`s are `object`s — no `.INSTANCE` indirection.
    pub(super) fn lift_fn(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(format!("{}.lift", as_ct.as_codetype().ffi_converter_name()))
    }

    /// `FfiConverter<Name>.lower` expression. Used at call sites to
    /// convert Kotlin values to their FFI representation before passing
    /// to a `UniffiLib` MethodHandle wrapper.
    pub(super) fn lower_fn(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(format!(
            "{}.lower",
            as_ct.as_codetype().ffi_converter_name()
        ))
    }

    /// `FfiConverter<Name>` object name. Used by templates that need to
    /// stamp the converter's identifier (e.g. the `object FfiConverterX`
    /// declaration that opens RecordTemplate.kt / OptionalTemplate.kt).
    pub(super) fn ffi_converter_name(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(as_ct.as_codetype().ffi_converter_name())
    }

    /// `FfiConverter<Name>.read` expression. Used by record / optional
    /// templates that read fields out of a `ByteBuffer`.
    pub(super) fn read_fn(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(format!("{}.read", as_ct.as_codetype().ffi_converter_name()))
    }

    /// `FfiConverter<Name>.write` expression — symmetric counterpart to
    /// `read_fn` for serialization back into a `ByteBuffer`.
    pub(super) fn write_fn(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(format!(
            "{}.write",
            as_ct.as_codetype().ffi_converter_name()
        ))
    }

    /// `FfiConverter<Name>.allocationSize` expression. Used by record /
    /// optional templates to size the destination `RustBuffer`.
    pub(super) fn allocation_size_fn(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(format!(
            "{}.allocationSize",
            as_ct.as_codetype().ffi_converter_name()
        ))
    }

    /// Returns the primitive call suffix (e.g. `"Long"`, `"Int"`) for the
    /// primitive-specialized `uniffiRustCall<T>` / `uniffiRustCallWithError<T>`
    /// helpers. Empty string for types where the Kotlin user type doesn't
    /// match the FFI primitive (Boolean ↔ Byte) or for non-primitive
    /// types — those go through `FfiConverter<Name>.lift` instead.
    pub(super) fn primitive_call_suffix(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(
            match as_ct.as_codetype().type_label_primitive().as_deref() {
                Some(s @ ("Byte" | "Short" | "Int" | "Long" | "Float" | "Double")) => s.to_string(),
                _ => String::new(),
            },
        )
    }

    /// True when the high-level Kotlin type matches the FFI primitive
    /// directly (no FfiConverter conversion needed). Used by the
    /// `func_decl` macro to decide whether an argument can be passed
    /// straight through to `UniffiLib.<fn>(...)` or needs `lower_fn`.
    /// Boolean is excluded — Kotlin `Boolean` ↔ FFI `Byte` requires
    /// conversion.
    pub(super) fn has_primitive_ffi_type(
        as_ct: &impl AsCodeType,
        _v: &dyn Values,
    ) -> Result<bool, askama::Error> {
        Ok(matches!(
            as_ct.as_codetype().type_label_primitive().as_deref(),
            Some("Byte" | "Short" | "Int" | "Long" | "Float" | "Double")
        ))
    }

    /// True when `type_` is a `Sequence<T>` whose element is a JVM
    /// primitive we've specialized into an unboxed array CodeType
    /// (`Int16Array`, `Int32Array`, `Int64Array`, `Float32Array`,
    /// `Float64Array`, `BooleanArray`). Used by the `wrapper.kt`
    /// per-type dispatch to *skip* emitting a generic `SequenceTemplate`
    /// for these — their `FfiConverter<Name>Array` definition already
    /// ships in the unconditional runtime helper block, and emitting
    /// `SequenceTemplate.kt` on top would produce a second `object`
    /// with the same name but `List<T>` instead of the unboxed array
    /// type (both writing to the same `FfiConverter<Name>Array.kt`
    /// filename, causing the file splitter's last-writer-wins to
    /// silently swap in the wrong definition).
    ///
    /// `Int8`/`UInt8` sequences fall through to the generic path since
    /// byte-array sequences come in via the separate `Bytes → ByteArray`
    /// route — matches the dispatch logic in `AsCodeType for
    /// Type::Sequence`.
    pub(super) fn is_primitive_array_sequence(
        type_: &uniffi_meta::Type,
        _v: &dyn Values,
    ) -> Result<bool, askama::Error> {
        use uniffi_meta::Type;
        Ok(matches!(
            type_,
            Type::Sequence { inner_type } if matches!(
                inner_type.as_ref(),
                Type::Int16 | Type::UInt16
                    | Type::Int32 | Type::UInt32
                    | Type::Int64 | Type::UInt64
                    | Type::Float32 | Type::Float64
                    | Type::Boolean,
            )
        ))
    }

    /// Convert any `AsType` to its underlying `FfiType`. Lets templates
    /// chain `{{ type_|ffi_type|ffi_value_layout }}` etc. without an
    /// intermediate let-binding.
    pub(super) fn ffi_type(type_: &impl AsType, _v: &dyn Values) -> Result<FfiType, askama::Error> {
        Ok(type_.as_type().into())
    }

    /// FFI type name (Kotlin primitive for scalars, `MemorySegment` for everything else).
    pub(super) fn ffi_type_name(type_: &FfiType, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_type_label(type_))
    }

    /// `ValueLayout` constant for `FunctionDescriptor` construction.
    pub(super) fn ffi_value_layout(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_value_layout(type_))
    }

    /// Cast suffix (`" as Long"`, `" as java.lang.foreign.MemorySegment"`, …)
    /// for `MethodHandle.invokeExact()` return values in Kotlin. Includes the
    /// leading space so the template can append it directly to the call site.
    pub(super) fn ffi_invoke_exact_cast(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_invoke_exact_cast(type_))
    }

    /// Whether the FFI type is a struct that needs a `SegmentAllocator`
    /// as the first argument to `invokeExact()`.
    pub(super) fn ffi_type_is_struct(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<bool, askama::Error> {
        Ok(KotlinCodeOracle.ffi_type_is_struct(type_))
    }
}
