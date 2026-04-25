/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI enums — covers all four shapes:
//! flat value enums (`enum class <Name>`), flat typed errors
//! (`sealed class <Name>Exception`), non-flat associated-data enums
//! (`sealed class <Name>` with per-variant nested data classes), and
//! the upcoming associated-data error variant (P3l-errors).
//!
//! The Java backend models errors as a separate `ErrorCodeType`, but
//! here the error-vs-enum distinction only matters for the class-name
//! rewrite (`Error → Exception`) and the template branch — both of
//! which live outside this struct. One `CodeType` covers everything.

use super::{CodeType, Config, KotlinCodeOracle};
use uniffi_bindgen::ComponentInterface;

#[derive(Debug)]
pub struct EnumCodeType {
    id: String,
}

impl EnumCodeType {
    pub fn new(id: String) -> Self {
        Self { id }
    }
}

impl CodeType for EnumCodeType {
    fn type_label(&self, ci: &ComponentInterface, _config: &Config) -> String {
        KotlinCodeOracle.class_name(ci, &self.id)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.id)
    }
}
