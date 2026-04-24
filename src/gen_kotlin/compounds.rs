/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impls for compound Kotlin types — `Optional<T>` as `T?`,
//! `Sequence<T>` as `List<T>`, `Map<K, V>` as `Map<K, V>`. Keeps the
//! lightweight "wrapped inner type" CodeTypes in one place so the
//! `AsCodeType for Type` dispatch stays a single-line-per-arm match.
//!
//! Primitive sequences (`Vec<i16>`, `Vec<i32>`, `Vec<i64>`, `Vec<f32>`,
//! `Vec<f64>`, `Vec<bool>`, and their unsigned counterparts) route to
//! specialized `{Int16,Int32,Int64,Float32,Float64,Boolean}ArrayCodeType`
//! that render as Kotlin's unboxed primitive arrays (`ShortArray`,
//! `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray`).
//! Mirrors the Java backend's `Int32ArrayCodeType` / `Float64ArrayCodeType`
//! / etc., and hits the same unboxed `int[]` / `double[]` / etc. JVM
//! types at runtime. `Vec<i8>` / `Vec<u8>` still fall through to the
//! generic `SequenceCodeType` — byte-array sequences come in via the
//! separate `Bytes` → `ByteArray` path.

use super::{AsCodeType, CodeType, Config};
use uniffi_bindgen::ComponentInterface;
use uniffi_meta::Type;

#[derive(Debug)]
pub struct OptionalCodeType {
    inner: Type,
}

impl OptionalCodeType {
    pub fn new(inner: Type) -> Self {
        Self { inner }
    }
}

impl CodeType for OptionalCodeType {
    fn type_label(&self, ci: &ComponentInterface, config: &Config) -> String {
        let inner = self.inner.as_codetype().type_label(ci, config);
        format!("{inner}?")
    }

    fn canonical_name(&self) -> String {
        format!("Optional{}", self.inner.as_codetype().canonical_name())
    }
}

#[derive(Debug)]
pub struct SequenceCodeType {
    inner: Type,
}

impl SequenceCodeType {
    pub fn new(inner: Type) -> Self {
        Self { inner }
    }
}

impl CodeType for SequenceCodeType {
    fn type_label(&self, ci: &ComponentInterface, config: &Config) -> String {
        format!("List<{}>", self.inner.as_codetype().type_label(ci, config))
    }

    fn canonical_name(&self) -> String {
        format!("Sequence{}", self.inner.as_codetype().canonical_name())
    }
}

#[derive(Debug)]
pub struct MapCodeType {
    key: Type,
    value: Type,
}

impl MapCodeType {
    pub fn new(key: Type, value: Type) -> Self {
        Self { key, value }
    }
}

impl CodeType for MapCodeType {
    fn type_label(&self, ci: &ComponentInterface, config: &Config) -> String {
        format!(
            "Map<{}, {}>",
            self.key.as_codetype().type_label(ci, config),
            self.value.as_codetype().type_label(ci, config),
        )
    }

    fn canonical_name(&self) -> String {
        // Mirrors Java's `MapKV` convention so FfiConverter identifiers
        // line up across both backends for shared uniffi metadata.
        format!(
            "Map{}{}",
            self.key.as_codetype().canonical_name(),
            self.value.as_codetype().canonical_name(),
        )
    }
}

// Unboxed-primitive array CodeTypes. One unit struct per primitive
// element type; `type_label` returns the Kotlin unboxed-array name
// (`IntArray`, `DoubleArray`, …) and `canonical_name` matches the Java
// backend's so the FfiConverter identifier lines up across both
// backends for shared uniffi metadata. Dispatch into these types
// happens in `AsCodeType for Type::Sequence`, which matches on the
// inner `Type` before falling through to the generic `SequenceCodeType`.
macro_rules! impl_primitive_array_code_type {
    ($T:ident, $type_label:literal, $canonical_name:literal) => {
        #[derive(Debug)]
        pub struct $T;

        impl CodeType for $T {
            fn type_label(&self, _ci: &ComponentInterface, _config: &Config) -> String {
                $type_label.into()
            }

            fn canonical_name(&self) -> String {
                $canonical_name.into()
            }
        }
    };
}

impl_primitive_array_code_type!(Int16ArrayCodeType, "ShortArray", "Int16Array");
impl_primitive_array_code_type!(Int32ArrayCodeType, "IntArray", "Int32Array");
impl_primitive_array_code_type!(Int64ArrayCodeType, "LongArray", "Int64Array");
impl_primitive_array_code_type!(Float32ArrayCodeType, "FloatArray", "Float32Array");
impl_primitive_array_code_type!(Float64ArrayCodeType, "DoubleArray", "Float64Array");
impl_primitive_array_code_type!(BooleanArrayCodeType, "BooleanArray", "BooleanArray");
