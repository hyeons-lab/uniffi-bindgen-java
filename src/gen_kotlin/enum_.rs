/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI enums — covers both flat value enums
//! (`enum class <Name>`) and flat typed errors (`sealed class
//! <Name>Exception`). Non-flat (sealed-variant / associated-data) enums
//! still panic in the wrapper dispatch; they land with a later phase.
//!
//! The Java backend models errors as a separate `ErrorCodeType`, but
//! here the error-vs-enum distinction only matters for the class-name
//! rewrite (`Error → Exception`) and the template branch — both of
//! which live outside this struct. One `CodeType` covers both.

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
        // Non-flat (sealed-variant / associated-data) enums land in a
        // later phase. Fail loudly at codegen time the first time a
        // fixture references one, mirroring the `FfiType::Struct` /
        // unsupported-Type panic strategy used elsewhere in this
        // backend. Without this guard the flat `enum class` template
        // would happily swallow the variant fields and produce
        // bindings that compile but corrupt the wire format on read.
        let def = ci.get_enum_definition(&self.id).unwrap_or_else(|| {
            panic!(
                "enum `{}` has no definition in the ComponentInterface",
                self.id
            )
        });
        assert!(
            def.is_flat(),
            "Kotlin CodeType not implemented for non-flat enum `{}` yet \
             (P3h+ adds sealed-variant enums). See gen_kotlin/enum_.rs.",
            self.id
        );
        KotlinCodeOracle.class_name(ci, &self.id)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.id)
    }
}
