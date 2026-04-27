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
    interface::{Argument, Callable, FfiDefinition, FfiType, Field},
};
use uniffi_meta::{AsType, Type};

pub use crate::gen_lang::CustomTypeConfig;
use crate::gen_lang::ExternalPackageResolver;

mod callback_interface;
mod compounds;
mod custom;
mod enum_;
mod miscellany;
mod object;
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

    /// `(interface_name, impl_class_name)` for a UniFFI object. They
    /// only differ for `[Trait, WithForeign]` objects, where the trait
    /// keeps the user's chosen name (so it appears in function
    /// signatures) and the Rust-side wrapper class is suffixed with
    /// `Impl`. Foreign Kotlin implementors of the trait satisfy the
    /// interface directly; the FfiConverter's LSB-dispatch routes
    /// between `<Name>Impl` (Rust handles, LSB=0) and
    /// `handleMap.remove` (foreign handles, LSB=1).
    ///
    /// Pure objects (no callback interface) get `(<Name>, <Name>)`.
    /// Diverges from Java's `<Name>Interface` suffix — Kotlin's
    /// `open class` + test-double idioms make a separate interface
    /// less load-bearing, so we keep snapshots tighter by skipping it.
    pub fn object_names(
        &self,
        ci: &ComponentInterface,
        obj: &uniffi_bindgen::interface::Object,
    ) -> (String, String) {
        let class_name = self.class_name(ci, obj.name());
        if obj.has_callback_interface() {
            let impl_name = format!("{class_name}Impl");
            (class_name, impl_name)
        } else {
            (class_name.clone(), class_name)
        }
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
            // Post-P3j-a the matching `UniffiXxx.LAYOUT` helper is
            // emitted by `NamespaceLibraryTemplate.kt`'s
            // `FfiDefinition::Struct` branch, so we can reference it
            // directly here — same shape as the Java backend.
            FfiType::Struct(name) => format!("{}.LAYOUT", self.ffi_struct_name(name)),
            FfiType::Callback(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => "java.lang.foreign.ValueLayout.ADDRESS".to_string(),
        }
    }

    /// Kotlin rendering of an FFI callback's public class name
    /// (`UniffiCallbackInterfaceFoo`). Matches the Java convention so
    /// the cross-backend shared uniffi metadata stays aligned.
    pub fn ffi_callback_name(&self, nm: &str) -> String {
        format!("Uniffi{}", nm.to_upper_camel_case())
    }

    /// Kotlin rendering of an FFI struct's public class name
    /// (`UniffiVTableCallbackInterfaceFoo`). Same convention as
    /// `ffi_callback_name`.
    pub fn ffi_struct_name(&self, nm: &str) -> String {
        format!("Uniffi{}", nm.to_upper_camel_case())
    }

    /// Resolve an `FfiType` to the enclosing helper class name
    /// (used when rendering a struct-field's `<Struct>.LAYOUT`).
    pub fn ffi_struct_type_name(&self, ffi_type: &FfiType) -> String {
        match ffi_type {
            FfiType::Struct(name) => self.ffi_struct_name(name),
            FfiType::RustBuffer(_) => "RustBuffer".to_string(),
            FfiType::RustCallStatus => "UniffiRustCallStatus".to_string(),
            FfiType::ForeignBytes => "ForeignBytes".to_string(),
            _ => panic!("ffi_struct_type_name called on non-struct type: {ffi_type:?}"),
        }
    }

    /// Whether an FFI type is a struct that sits embedded (by value)
    /// inside another struct — in that case its field access goes
    /// through `MemorySegment.asSlice` / `MemorySegment.copy` rather
    /// than `seg.get(layout, offset)` / `seg.set(layout, offset, v)`.
    pub fn ffi_type_is_embedded_struct(&self, ffi_type: &FfiType) -> bool {
        matches!(
            ffi_type,
            FfiType::RustBuffer(_)
                | FfiType::RustCallStatus
                | FfiType::ForeignBytes
                | FfiType::Struct(_)
        )
    }

    /// Unaligned `ValueLayout` variant used for struct-field access.
    /// The JVM aligns top-level value layouts to their natural
    /// alignment, but fields inside a packed struct can land on any
    /// byte boundary — reading them with the aligned layout will
    /// `IllegalArgumentException` at runtime. Java's
    /// `ffi_value_layout_unaligned` exists for the same reason.
    pub fn ffi_value_layout_unaligned(&self, ffi_type: &FfiType) -> String {
        match ffi_type {
            FfiType::Int8 | FfiType::UInt8 => "java.lang.foreign.ValueLayout.JAVA_BYTE".to_string(),
            FfiType::Int16 | FfiType::UInt16 => {
                "java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED".to_string()
            }
            FfiType::Int32 | FfiType::UInt32 => {
                "java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED".to_string()
            }
            FfiType::Int64 | FfiType::UInt64 | FfiType::Handle => {
                "java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED".to_string()
            }
            FfiType::Float32 => "java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED".to_string(),
            FfiType::Float64 => "java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED".to_string(),
            FfiType::Callback(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => {
                "java.lang.foreign.ValueLayout.ADDRESS_UNALIGNED".to_string()
            }
            // Struct types use slice-based access, not ValueLayout
            // access — fall back to the aligned layout; the caller
            // should be branching on `ffi_type_is_embedded_struct`
            // before asking for a ValueLayout at all.
            _ => self.ffi_value_layout(ffi_type),
        }
    }

    /// Natural alignment (bytes) of an FFI type. Used by the layout
    /// generator to emit padding fields so the Kotlin struct matches
    /// the Rust repr(C) layout.
    pub fn ffi_type_alignment(&self, ffi_type: &FfiType) -> usize {
        match ffi_type {
            FfiType::Int8 | FfiType::UInt8 => 1,
            FfiType::Int16 | FfiType::UInt16 => 2,
            FfiType::Int32 | FfiType::UInt32 | FfiType::Float32 => 4,
            FfiType::Int64 | FfiType::UInt64 | FfiType::Float64 | FfiType::Handle => 8,
            FfiType::Callback(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => 8,
            // Embedded structs — max alignment of their fields, all
            // happen to be 8-byte aligned for uniffi's built-ins.
            FfiType::RustBuffer(_)
            | FfiType::RustCallStatus
            | FfiType::ForeignBytes
            | FfiType::Struct(_) => 8,
        }
    }

    /// Size (bytes) of an FFI type as it appears inside a parent
    /// struct. Hardcoded for uniffi's built-in structs (RustBuffer,
    /// RustCallStatus, ForeignBytes); returns 0 for user structs
    /// (unknown at codegen time — the layout generator falls back on
    /// the alignment alone in that case).
    pub fn ffi_type_size(&self, ffi_type: &FfiType) -> usize {
        match ffi_type {
            FfiType::Int8 | FfiType::UInt8 => 1,
            FfiType::Int16 | FfiType::UInt16 => 2,
            FfiType::Int32 | FfiType::UInt32 | FfiType::Float32 => 4,
            FfiType::Int64 | FfiType::UInt64 | FfiType::Float64 | FfiType::Handle => 8,
            FfiType::Callback(_)
            | FfiType::VoidPointer
            | FfiType::Reference(_)
            | FfiType::MutReference(_) => 8,
            FfiType::RustBuffer(_) => 24,
            FfiType::RustCallStatus => 32,
            FfiType::ForeignBytes => 16,
            FfiType::Struct(_) => 0,
        }
    }

    /// Layout of an FFI type when it appears as a field inside a
    /// parent struct. Differs from `ffi_value_layout` only for
    /// structs-inside-structs: those use their full `.LAYOUT` rather
    /// than an `ADDRESS` pointer reference.
    pub fn ffi_struct_field_layout(&self, ffi_type: &FfiType) -> String {
        match ffi_type {
            FfiType::RustBuffer(_) => "RustBuffer.LAYOUT".to_string(),
            FfiType::RustCallStatus => "UniffiRustCallStatus.LAYOUT".to_string(),
            FfiType::ForeignBytes => "ForeignBytes.LAYOUT".to_string(),
            FfiType::Struct(name) => format!("{}.LAYOUT", self.ffi_struct_name(name)),
            _ => self.ffi_value_layout(ffi_type),
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

    /// Optional zero-arg function that must run at library init time —
    /// e.g. `UniffiCallbackInterfaceFoo.register` for a callback
    /// interface. Collected via `iter_local_types()` by
    /// `KotlinWrapper::initialization_fns()` and called in order from
    /// `UniffiLib`'s `init {}` block. Default `None`; only
    /// callback-interface (and the object-with-callback-interface
    /// flavor, P3j-c) types override.
    fn initialization_fn(&self) -> Option<String> {
        None
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
            Type::Sequence { inner_type } => {
                // Unbox once, match on the owned `Type`. Avoids the
                // NLL-reliant "borrow from `.as_ref()` then move out of
                // `*inner_type` in the fallback arm" pattern the Java
                // backend sidesteps with an explicit `.clone()`.
                let inner_type = *inner_type;
                match inner_type {
                    Type::Int16 | Type::UInt16 => Box::new(compounds::Int16ArrayCodeType),
                    Type::Int32 | Type::UInt32 => Box::new(compounds::Int32ArrayCodeType),
                    Type::Int64 | Type::UInt64 => Box::new(compounds::Int64ArrayCodeType),
                    Type::Float32 => Box::new(compounds::Float32ArrayCodeType),
                    Type::Float64 => Box::new(compounds::Float64ArrayCodeType),
                    Type::Boolean => Box::new(compounds::BooleanArrayCodeType),
                    // Int8/UInt8 fall through to the generic path —
                    // byte-array sequences come in via the separate
                    // `Type::Bytes` → `ByteArray` route. Mirrors the
                    // Java backend's shape.
                    other => Box::new(compounds::SequenceCodeType::new(other)),
                }
            }
            Type::Map {
                key_type,
                value_type,
            } => Box::new(compounds::MapCodeType::new(*key_type, *value_type)),
            Type::Object { name, imp, .. } => Box::new(object::ObjectCodeType::new(name, imp)),
            Type::CallbackInterface { name, .. } => {
                Box::new(callback_interface::CallbackInterfaceCodeType::new(name))
            }
            Type::Custom { name, .. } => Box::new(custom::CustomCodeType::new(name)),
            Type::Timestamp => Box::new(miscellany::TimestampCodeType),
            Type::Duration => Box::new(miscellany::DurationCodeType),
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

impl AsCodeType for &'_ uniffi_bindgen::interface::CallbackInterface {
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

// `cdylib_name` is consumed by `NamespaceLibraryTemplate.kt` (resolves
// the symbol passed to `System.loadLibrary`); `android` is defined for
// TOML-schema parity with the Java backend but no Kotlin template reads
// it yet, so it stays `#[allow(dead_code)]` until a subsequent phase
// hooks Android-specific codegen up.
impl Config {
    pub fn package_name(&self) -> String {
        self.package_name.clone().unwrap_or_else(|| "uniffi".into())
    }

    pub fn cdylib_name(&self) -> String {
        self.cdylib_name.clone().unwrap_or_else(|| "uniffi".into())
    }

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

    /// Kotlin namespace-object name. PascalCase from the crate's
    /// snake_case namespace (e.g. `primitive_arrays` → `PrimitiveArrays`),
    /// matching the Java backend's behaviour and Kotlin class-naming
    /// conventions.
    pub fn namespace_class_name(&self) -> String {
        self.ci.namespace().to_upper_camel_case()
    }

    /// Ordered list of zero-arg `UniffiCallbackInterface<Name>.register`
    /// calls that `UniffiLib`'s `init {}` block must invoke so every
    /// callback interface's vtable gets wired into Rust before user
    /// code can hand off a foreign implementation. Collected by
    /// walking `iter_local_types()` and asking each CodeType for its
    /// `initialization_fn()` — `None` for everything except callback
    /// interfaces (and, in a later phase, objects that carry a
    /// callback interface implementation).
    pub fn initialization_fns(&self) -> Vec<String> {
        self.ci
            .iter_local_types()
            .filter_map(|t| t.clone().as_codetype().initialization_fn())
            .collect()
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
    use uniffi_bindgen::interface::{Callable, FfiType, Variant};
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

    /// lowerCamelCase variable name without the reserved-word fixup.
    /// Used for FFI struct field names where the string needs to match
    /// exactly between `MemoryLayout.*.withName(...)` in the struct
    /// layout and `PathElement.groupElement(...)` when resolving byte
    /// offsets — otherwise `byteOffset()` throws at class-init.
    pub(super) fn var_name_raw<S: AsRef<str>>(
        nm: S,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.var_name_raw(nm.as_ref()))
    }

    /// Kotlin-idiomatic function name. Same casing rules as `var_name`.
    pub(super) fn fn_name<S: AsRef<str>>(nm: S, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.fn_name(nm.as_ref()))
    }

    /// Strip the surrounding backticks that `var_name` adds for Kotlin
    /// reserved words. Used by templates that need to render a name as
    /// human-readable text rather than a Kotlin identifier — e.g. the
    /// synthetic `field=value` message attached to non-flat error
    /// variants, where `` `object`=… `` would leak backticks into the
    /// user-visible exception message. Mirrors Java's `unquote` filter.
    pub(super) fn unquote<S: AsRef<str>>(nm: S, _v: &dyn Values) -> Result<String, askama::Error> {
        Ok(nm.as_ref().trim_matches('`').to_string())
    }

    /// UpperCamelCase class name + reserved-word / error-suffix fixup.
    /// Used by templates that need to stamp a class identifier derived
    /// from a raw uniffi name — e.g. `UniffiCallbackInterfaceImpl`'s
    /// per-method `{{ meth.name()|class_name(ci) }}Callback` helpers.
    pub(super) fn class_name<S: AsRef<str>>(
        nm: S,
        _v: &dyn Values,
        ci: &ComponentInterface,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.class_name(ci, nm.as_ref()))
    }

    /// `(interface_name, impl_class_name)` for an Object. Matches
    /// when an object is `[Trait, WithForeign]`: the interface keeps
    /// the user's name (for use in function signatures), the
    /// Rust-side wrapper class gets a `<Name>Impl` suffix.
    pub(super) fn object_names(
        obj: &uniffi_bindgen::interface::Object,
        _v: &dyn Values,
        ci: &ComponentInterface,
    ) -> Result<(String, String), askama::Error> {
        Ok(KotlinCodeOracle.object_names(ci, obj))
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

    /// Public class name for an FFI callback function (`UniffiXxx`
    /// for a callback named `xxx`).
    pub(super) fn ffi_callback_name<S: AsRef<str>>(
        nm: S,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_callback_name(nm.as_ref()))
    }

    /// Public class name for an FFI struct (`UniffiXxx`).
    pub(super) fn ffi_struct_name<S: AsRef<str>>(
        nm: S,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_struct_name(nm.as_ref()))
    }

    /// `pollFunc` argument for `uniffiRustCallAsync` — a method
    /// reference into `UniffiLib`'s `ffi_<crate>_rust_future_poll_<T>`
    /// stub (where `<T>` is the return-type-specialized FFI suffix
    /// uniffi-rs picks). Mirrors Java's `async_poll` filter.
    pub(super) fn async_poll(
        callable: impl Callable,
        _v: &dyn Values,
        ci: &ComponentInterface,
    ) -> Result<String, askama::Error> {
        Ok(format!("UniffiLib::{}", callable.ffi_rust_future_poll(ci)))
    }

    /// `completeFunc` argument for `uniffiRustCallAsync` — a lambda
    /// that calls `UniffiLib`'s `ffi_<crate>_rust_future_complete_<T>`
    /// stub. Conditionally prepends `_allocator` when the FFI return
    /// is a struct (`RustBuffer` for non-primitive types).
    pub(super) fn async_complete(
        callable: impl Callable,
        _v: &dyn Values,
        ci: &ComponentInterface,
    ) -> Result<String, askama::Error> {
        let ffi_func = callable.ffi_rust_future_complete(ci);
        let needs_allocator = callable.return_type().is_some_and(|t| {
            let ffi_type: FfiType = t.into();
            KotlinCodeOracle.ffi_type_is_struct(&ffi_type)
        });
        let body = if needs_allocator {
            format!("UniffiLib.{ffi_func}(_allocator, future, status)")
        } else {
            format!("UniffiLib.{ffi_func}(future, status)")
        };
        Ok(format!("{{ _allocator, future, status -> {body} }}"))
    }

    /// `freeFunc` argument for `uniffiRustCallAsync` — method reference
    /// into `UniffiLib`'s `ffi_<crate>_rust_future_free_<T>` stub.
    pub(super) fn async_free(
        callable: impl Callable,
        _v: &dyn Values,
        ci: &ComponentInterface,
    ) -> Result<String, askama::Error> {
        Ok(format!("UniffiLib::{}", callable.ffi_rust_future_free(ci)))
    }

    /// Enclosing helper-class name for a struct-shaped FfiType.
    pub(super) fn ffi_struct_type_name(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_struct_type_name(type_))
    }

    /// True when the FFI type sits embedded (by value) inside another
    /// struct — field access goes through `MemorySegment.asSlice` /
    /// `MemorySegment.copy` rather than `seg.get / seg.set`.
    pub(super) fn ffi_type_is_embedded_struct(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<bool, askama::Error> {
        Ok(KotlinCodeOracle.ffi_type_is_embedded_struct(type_))
    }

    /// Unaligned `ValueLayout` constant (used for struct-field access
    /// where the field may land on any byte boundary).
    pub(super) fn ffi_value_layout_unaligned(
        type_: &FfiType,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        Ok(KotlinCodeOracle.ffi_value_layout_unaligned(type_))
    }

    /// Generates the `structLayout(...)` body for an FfiStruct — a
    /// comma-separated list of per-field layouts with computed
    /// padding so the Kotlin struct matches the Rust `#[repr(C)]`
    /// layout. Mirrors the Java `ffi_struct_layout_body` filter
    /// byte-for-byte.
    pub(super) fn ffi_struct_layout_body(
        ffi_struct: &uniffi_bindgen::interface::FfiStruct,
        _v: &dyn Values,
    ) -> Result<String, askama::Error> {
        let oracle = KotlinCodeOracle;
        let mut parts = Vec::new();
        let mut offset: usize = 0;
        for field in ffi_struct.fields() {
            let field_type = field.type_();
            let alignment = oracle.ffi_type_alignment(&field_type);
            let padding = if !offset.is_multiple_of(alignment) {
                alignment - (offset % alignment)
            } else {
                0
            };
            if padding > 0 {
                parts.push(format!(
                    "java.lang.foreign.MemoryLayout.paddingLayout({padding})"
                ));
                offset += padding;
            }
            let layout = oracle.ffi_struct_field_layout(&field_type);
            let field_name = oracle.var_name_raw(field.name());
            parts.push(format!("{layout}.withName(\"{field_name}\")"));
            let size = oracle.ffi_type_size(&field_type);
            if size > 0 {
                offset += size;
            } else {
                offset = 0;
            }
        }
        Ok(parts.join(",\n        "))
    }
}
