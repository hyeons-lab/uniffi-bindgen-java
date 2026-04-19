/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Kotlin backend skeleton (P2).
//!
//! This is the first Kotlin code in the repo. P2 only needs to prove the
//! pipeline: CLI flag dispatch, `Config` parsing, marker-based file
//! splitting, and kotlinc-valid output. The `generate_bindings` function
//! below emits a single hand-written Kotlin file per UniFFI namespace —
//! no real type mapping yet. P3+ will grow the backend into an Askama
//! templated generator paralleling `gen_java/`.

use std::collections::HashMap;

use anyhow::Result;
use serde::{Deserialize, Serialize};
use uniffi_bindgen::ComponentInterface;

pub use crate::gen_lang::CustomTypeConfig;
use crate::gen_lang::ExternalPackageResolver;

/// Kotlin-specific config. Field set mirrors the language-neutral subset of
/// `gen_java::Config` plus Kotlin-only knobs (none yet in P2; `android`
/// plumbed through so P4+ cleaner code can pick it up without another
/// refactor).
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub(super) package_name: Option<String>,
    pub(super) cdylib_name: Option<String>,
    #[serde(default)]
    custom_types: HashMap<String, CustomTypeConfig>,
    #[serde(default)]
    pub(super) external_packages: HashMap<String, String>,
    #[serde(default)]
    android: bool,
    #[serde(default)]
    pub(super) rename: toml::Table,
    #[serde(default)]
    omit_checksums: bool,
}

// `cdylib_name`, `omit_checksums`, and `android` are defined now so the
// TOML schema matches the Java backend and later phases don't churn
// `gen_kotlin::Config` for every new consumer. `#[allow(dead_code)]` until
// P3+ hooks them up.
impl Config {
    pub fn package_name(&self) -> String {
        self.package_name.clone().unwrap_or_else(|| "uniffi".into())
    }

    #[allow(dead_code)]
    pub fn cdylib_name(&self) -> String {
        self.cdylib_name.clone().unwrap_or_else(|| "uniffi".into())
    }

    #[allow(dead_code)]
    pub fn omit_checksums(&self) -> bool {
        self.omit_checksums
    }

    /// Whether to generate PanamaPort imports for Android compatibility.
    /// Mirrors the `android` flag in the Java config. P4+ consumes this in
    /// the cleaner-helper template.
    #[allow(dead_code)]
    pub fn android(&self) -> bool {
        self.android
    }
}

impl ExternalPackageResolver for Config {
    fn external_type_package_name(&self, module_path: &str, namespace: &str) -> String {
        let crate_name = module_path.split("::").next().unwrap();
        match self.external_packages.get(crate_name) {
            Some(name) => name.clone(),
            None => format!("uniffi.{namespace}"),
        }
    }
}

/// Emit a hand-written skeleton Kotlin file for the given namespace.
///
/// P2-only: a single `object <Namespace>` stub per component, enough to
/// prove that the splitter + CLI + Config + file-extension plumbing all
/// work end-to-end. `cargo run -- generate --language kotlin ...` should
/// produce a valid `.kt` file that `kotlinc` accepts without errors.
pub fn generate_bindings(config: &Config, ci: &ComponentInterface) -> Result<String> {
    let ns = ci.namespace();
    let mut chars = ns.chars();
    let ns_class = match chars.next() {
        Some(c) => c.to_uppercase().chain(chars).collect::<String>(),
        None => String::new(),
    };
    let package = config.package_name();

    Ok(format!(
        "\n// UNIFFI:FILE {ns_class}.kt\n\
         package {package}\n\
         \n\
         // Skeleton output from the P2 Kotlin backend. Real type mapping\n\
         // lands in later phases — see the README for the current state.\n\
         object {ns_class}\n",
    ))
}
