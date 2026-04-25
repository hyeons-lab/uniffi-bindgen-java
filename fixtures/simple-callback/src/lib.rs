/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Minimal canary for P3j-b callback-interface codegen. One pure
//! `callback_interface` (Rust never implements it — only the foreign
//! side does) and a namespace function that takes an instance of it
//! and calls one method. No throws, no async, no errors, no trait
//! methods. Kept deliberately narrow so the Kotlin snapshot stays
//! auditable and the canary fails loudly on any regression in the
//! vtable-assembly path.

uniffi::setup_scaffolding!("simple_callback");

#[uniffi::export(callback_interface)]
pub trait Greeter: Send + Sync {
    fn greet(&self, name: String) -> String;
}

#[uniffi::export]
fn invoke_greeter(greeter: Box<dyn Greeter>, name: String) -> String {
    greeter.greet(name)
}
