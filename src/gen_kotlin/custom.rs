/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI custom types — user-defined newtypes
//! (e.g. `custom Handle<Int64>` → Kotlin wrapper type around `Long`).
//! Mirrors `gen_java/custom.rs` structurally; the templates differ
//! only in Kotlin idiom (data class vs. Java record, `object`
//! singleton FfiConverter vs. enum-INSTANCE).

use super::{CodeType, Config, KotlinCodeOracle};
use uniffi_bindgen::ComponentInterface;

#[derive(Debug)]
pub struct CustomCodeType {
    name: String,
}

impl CustomCodeType {
    pub fn new(name: String) -> Self {
        CustomCodeType { name }
    }
}

impl CodeType for CustomCodeType {
    fn type_label(&self, ci: &ComponentInterface, _config: &Config) -> String {
        KotlinCodeOracle.class_name(ci, &self.name)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.name)
    }
}
