/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI records, rendered as Kotlin `data class`.
//!
//! Mirrors `gen_java/record.rs` structurally. The canonical name (used
//! to derive the FfiConverter identifier) follows uniffi's
//! `Type{name}` convention, matching the Java backend so that
//! cross-backend metadata expectations stay aligned.

use super::{CodeType, Config, KotlinCodeOracle};
use uniffi_bindgen::ComponentInterface;

#[derive(Debug)]
pub struct RecordCodeType {
    id: String,
}

impl RecordCodeType {
    pub fn new(id: String) -> Self {
        Self { id }
    }
}

impl CodeType for RecordCodeType {
    fn type_label(&self, ci: &ComponentInterface, _config: &Config) -> String {
        KotlinCodeOracle.class_name(ci, &self.id)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.id)
    }
}
