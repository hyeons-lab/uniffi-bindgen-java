/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI objects (interfaces), rendered as Kotlin
//! handle-based wrapper classes. Mirrors `gen_java/object.rs`
//! structurally. The Java backend carries an `ObjectImpl` field for
//! callback-interface dispatch; the Kotlin backend tracks that too so
//! the `AsCodeType` arm can route correctly once P3j lands. Until then
//! `ObjectTemplate.kt` only renders the no-callback-interface case, and
//! any object with `imp.has_callback_interface()` will surface loudly
//! (unsupported type in `gen_kotlin/mod.rs`).

use super::{CodeType, Config, KotlinCodeOracle};
use uniffi_bindgen::{ComponentInterface, interface::ObjectImpl};

#[derive(Debug)]
pub struct ObjectCodeType {
    name: String,
    #[allow(dead_code)] // Consumed in P3j when callback-interface dispatch lands.
    imp: ObjectImpl,
}

impl ObjectCodeType {
    pub fn new(name: String, imp: ObjectImpl) -> Self {
        Self { name, imp }
    }
}

impl CodeType for ObjectCodeType {
    fn type_label(&self, ci: &ComponentInterface, _config: &Config) -> String {
        KotlinCodeOracle.class_name(ci, &self.name)
    }

    fn canonical_name(&self) -> String {
        format!("Type{}", self.name)
    }
}
