/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI callback interfaces (pure
//! foreign-implemented traits). Parallel to `gen_java/callback_interface.rs`.
//! The only meaningful difference is the `initialization_fn`: Kotlin
//! emits an `object UniffiCallbackInterface<Name>` singleton (not a
//! class with a static INSTANCE field), so the registration call is
//! `UniffiCallbackInterface<Name>.register` — no `.INSTANCE`
//! indirection like the Java backend needs.

use super::{CodeType, Config, KotlinCodeOracle};
use uniffi_bindgen::ComponentInterface;

#[derive(Debug)]
pub struct CallbackInterfaceCodeType {
    id: String,
}

impl CallbackInterfaceCodeType {
    pub fn new(id: String) -> Self {
        Self { id }
    }
}

impl CodeType for CallbackInterfaceCodeType {
    fn type_label(&self, ci: &ComponentInterface, _config: &Config) -> String {
        KotlinCodeOracle.class_name(ci, &self.id)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.id)
    }

    fn initialization_fn(&self) -> Option<String> {
        Some(format!("UniffiCallbackInterface{}.register", self.id))
    }
}
