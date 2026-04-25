/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Minimal canary for P3j-c `[Trait, WithForeign]` codegen — trait
//! objects that can be implemented in EITHER Rust or the foreign
//! language. Exercises:
//!   * `interface Counter` user-facing trait declaration.
//!   * `class CounterImpl(handle: Long)` wrapper for Rust-side
//!     instances, also implementing the `Counter` interface.
//!   * `FfiConverterTypeCounter` with LSB-tagged lift/lower:
//!     even handles → `CounterImpl` (Rust), odd handles →
//!     `handleMap.remove` (foreign).
//!   * `UniffiCallbackInterfaceCounter.register()` invoked at
//!     `UniffiLib.init` time for foreign-vtable wiring.
//!
//! Single trait with a single non-throws sync method. Both a
//! Rust-impl factory and a generic round-trip function so the
//! Kotlin canary exercises both directions of handle ownership.

use std::sync::Arc;
use std::sync::Mutex;

uniffi::setup_scaffolding!("trait_with_foreign");

#[uniffi::export(with_foreign)]
pub trait Counter: Send + Sync {
    fn next_value(&self) -> i32;
}

struct RustCounter {
    state: Mutex<i32>,
}

impl Counter for RustCounter {
    fn next_value(&self) -> i32 {
        let mut s = self.state.lock().unwrap();
        *s += 1;
        *s
    }
}

#[uniffi::export]
fn make_rust_counter(start: i32) -> Arc<dyn Counter> {
    Arc::new(RustCounter {
        state: Mutex::new(start),
    })
}

#[uniffi::export]
fn use_counter(counter: Arc<dyn Counter>) -> i32 {
    counter.next_value()
}
