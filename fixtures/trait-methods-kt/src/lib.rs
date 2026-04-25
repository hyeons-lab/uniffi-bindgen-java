/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Minimal canary for P3l-traits codegen: `#[uniffi::export(...)]`
//! trait method exports on Records and Objects. Each side exercises
//! all four override targets — Display→toString, Eq→equals,
//! Hash→hashCode, Ord→compareTo — so the snapshot covers every arm
//! of the new `uniffi_trait_impls` macro.

use std::cmp::Ordering;
use std::hash::{Hash, Hasher};
use std::sync::Arc;

#[derive(Debug, uniffi::Record)]
#[uniffi::export(Display, Eq, Ord, Hash)]
pub struct TraitRec {
    s: String,
    i: i32,
}

// Custom impls so the trait outputs are non-trivial: equality and
// ordering ignore `i`, hashing ignores `i`. Mirrors the upstream
// `uniffi-fixture-trait-methods` style — non-derive impls let tests
// distinguish the trait-routed override from the language default.
impl std::fmt::Display for TraitRec {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "TraitRec(s={})", self.s)
    }
}

impl PartialEq for TraitRec {
    fn eq(&self, other: &Self) -> bool {
        self.s == other.s
    }
}

impl Eq for TraitRec {}

impl PartialOrd for TraitRec {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for TraitRec {
    fn cmp(&self, other: &Self) -> Ordering {
        self.s.cmp(&other.s)
    }
}

impl Hash for TraitRec {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.s.hash(state)
    }
}

#[derive(Debug, uniffi::Object)]
#[uniffi::export(Display, Eq, Ord, Hash)]
pub struct TraitObj {
    val: String,
}

#[uniffi::export]
impl TraitObj {
    #[uniffi::constructor]
    fn new(val: String) -> Arc<Self> {
        Arc::new(Self { val })
    }
}

impl std::fmt::Display for TraitObj {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "TraitObj({})", self.val)
    }
}

impl PartialEq for TraitObj {
    fn eq(&self, other: &Self) -> bool {
        self.val == other.val
    }
}

impl Eq for TraitObj {}

impl PartialOrd for TraitObj {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for TraitObj {
    fn cmp(&self, other: &Self) -> Ordering {
        self.val.cmp(&other.val)
    }
}

impl Hash for TraitObj {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.val.hash(state)
    }
}

uniffi::setup_scaffolding!("trait_methods_kt");
