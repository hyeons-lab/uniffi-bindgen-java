/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impls for compound Kotlin types — currently just `Optional<T>`,
//! rendered as `T?`. Sequences and Maps land alongside the fixtures that
//! exercise them (P3g+).

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
