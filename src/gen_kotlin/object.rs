/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impl for UniFFI objects (interfaces), rendered as Kotlin
//! handle-based wrapper classes. Mirrors `gen_java/object.rs`
//! structurally. The `imp` field captures whether the object was
//! declared `[Trait, WithForeign]` — `imp.has_callback_interface()`
//! flips two bits of behavior:
//!   * `initialization_fn` returns `Some(...)` so the object's vtable
//!     gets registered with Rust at `UniffiLib.init` time (P3j-c).
//!   * `ObjectTemplate.kt` emits a separate `interface <Name>` and
//!     a `class <Name>Impl` wrapper, with the FfiConverter doing
//!     LSB-tagged lift/lower dispatch between Rust handles (even)
//!     and foreign-implementor handles (odd).

use super::{CodeType, Config, KotlinCodeOracle};
use uniffi_bindgen::{ComponentInterface, interface::ObjectImpl};

#[derive(Debug)]
pub struct ObjectCodeType {
    name: String,
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

    fn initialization_fn(&self) -> Option<String> {
        // `[Trait, WithForeign]` objects need the same vtable
        // registration at library init that pure callback interfaces
        // do — the Rust side has to know how to dispatch back to a
        // foreign implementor.
        self.imp
            .has_callback_interface()
            .then(|| format!("UniffiCallbackInterface{}.register", self.name))
    }
}
