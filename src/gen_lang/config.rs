/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Language-neutral config pieces reusable across backends.

use serde::{Deserialize, Serialize};
use uniffi_bindgen::ComponentInterface;

/// Resolve a language-level package name for a type that may live in another
/// crate. Implemented per-backend because "package" syntax and defaulting
/// rules differ between Java, Kotlin, etc.
pub trait ExternalPackageResolver {
    /// Return the fully qualified package that owns types coming from the
    /// given Rust `module_path` (crate). `namespace` is the UniFFI namespace
    /// used as the fallback when the caller has no explicit override.
    fn external_type_package_name(&self, module_path: &str, namespace: &str) -> String;
}

/// If `type_name` names a type that lives in a different crate, prepend the
/// external package so the generated reference resolves. Local types are
/// returned unchanged.
pub fn potentially_add_external_package<C: ExternalPackageResolver>(
    config: &C,
    ci: &ComponentInterface,
    type_name: &str,
    display_name: String,
) -> String {
    match ci.get_type(type_name) {
        Some(typ) => {
            if ci.is_external(&typ) {
                let module_path = typ.module_path().unwrap();
                // The resolver's `namespace` argument is the fallback used
                // when the config has no explicit mapping for `module_path`.
                // It must be the UniFFI namespace that owns the type, not the
                // rendered display label (which is what the pre-P1.3 code
                // accidentally passed — harmless in practice because every
                // fixture sets explicit `external_packages`, but still wrong).
                let namespace = ci
                    .namespace_for_module_path(module_path)
                    .unwrap_or(&display_name);
                format!(
                    "{}.{}",
                    config.external_type_package_name(module_path, namespace),
                    display_name
                )
            } else {
                display_name
            }
        }
        None => display_name,
    }
}

/// User-supplied custom-type config (lift/lower converters, optional imports,
/// optional renamed host-language type). Wire format is shared across
/// backends; only the backend-specific `imports` keys differ in practice.
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct CustomTypeConfig {
    pub imports: Option<Vec<String>>,
    pub type_name: Option<String>,
    pub into_custom: String, // backcompat alias for lift
    pub lift: String,
    pub from_custom: String, // backcompat alias for lower
    pub lower: String,
}

impl CustomTypeConfig {
    pub fn lift(&self, name: &str) -> String {
        let converter = if self.lift.is_empty() {
            &self.into_custom
        } else {
            &self.lift
        };
        converter.replace("{}", name)
    }

    pub fn lower(&self, name: &str) -> String {
        let converter = if self.lower.is_empty() {
            &self.from_custom
        } else {
            &self.lower
        };
        converter.replace("{}", name)
    }
}
