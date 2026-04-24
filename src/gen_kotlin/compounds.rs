/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impls for compound Kotlin types — `Optional<T>` as `T?`,
//! `Sequence<T>` as `List<T>`, `Map<K, V>` as `Map<K, V>`. Keeps the
//! lightweight "wrapped inner type" CodeTypes in one place so the
//! `AsCodeType for Type` dispatch stays a single-line-per-arm match.
//!
//! Primitive sequences (`Vec<i32>`, `Vec<f64>`, …) also route here
//! and render as boxed `List<Int>` / `List<Double>`. The Java backend
//! short-circuits those to primitive arrays (`int[]`, `double[]`)
//! for throughput; the Kotlin parallel (`IntArray`, `DoubleArray`) is
//! tracked as a follow-up phase since each primitive needs its own
//! `FfiConverter…Array` object + runtime helper.

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
