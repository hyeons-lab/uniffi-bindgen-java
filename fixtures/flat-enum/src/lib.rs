/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Minimal canary for P3g flat-enum codegen. Exercises a flat
//! `enum class` declaration and its FfiConverter read/write path —
//! nothing else, so the Kotlin snapshot stays auditable.
//!
//! `uniffi-fixture-enum-types` in uniffi-rs covers this territory but
//! drags in sealed-variant enums, records, and objects; most of that
//! is unsupported in Kotlin until P3h+, so using it here would either
//! panic or silently produce wrong bindings.

uniffi::setup_scaffolding!("flat_enum");

#[derive(uniffi::Enum)]
pub enum Animal {
    Dog,
    Cat,
}

#[uniffi::export]
fn round_trip(animal: Animal) -> Animal {
    animal
}
