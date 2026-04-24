/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Minimal canary for P3h map codegen. Pairs with the existing
//! `uniffi-fixture-primitive-arrays` (which exercises sequences) — this
//! fixture handles the complementary `Map<K, V>` path. Deliberately
//! small: a single `HashMap<String, i32>` round-trip, nothing else,
//! so the Kotlin snapshot stays auditable.

uniffi::setup_scaffolding!("string_map");

#[uniffi::export]
fn round_trip(m: std::collections::HashMap<String, i32>) -> std::collections::HashMap<String, i32> {
    m
}
