/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Kotlin backend. P2 wired up the CLI dispatch and emitted a hand-written
//! namespace stub; P3a upgrades that to Askama template rendering so
//! subsequent phases can add real type converters, the FFM runtime, etc.
//! without rewriting the pipeline.
//!
//! Still bounded for P3a: the `wrapper.kt` template emits the same P2
//! skeleton (an `object <Namespace>`). P3b introduces the FFM runtime +
//! primitive FfiConverters; P3c records; P3d flat enums; P3e reserved-
//! word handling.

use std::collections::HashMap;

use anyhow::{Context, Result};
use askama::Template;
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
// subsequent phases hook them up.
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
    /// Mirrors the `android` flag in the Java config. Later phases consume
    /// this in the cleaner-helper template.
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

/// Askama-rendered root template. Mirrors `gen_java::JavaWrapper` at the
/// structural level but points at Kotlin templates (`syntax = "kotlin"`,
/// `path = "wrapper.kt"`). The template body is intentionally thin for
/// P3a — it renders the same namespace-object skeleton as the P2
/// hand-written output. P3b+ adds `{% include %}` directives for the
/// FFM runtime + per-type converter templates.
#[derive(Template)]
#[template(syntax = "kotlin", escape = "none", path = "wrapper.kt")]
pub struct KotlinWrapper<'a> {
    config: Config,
    ci: &'a ComponentInterface,
}

impl<'a> KotlinWrapper<'a> {
    pub fn new(config: Config, ci: &'a ComponentInterface) -> Self {
        Self { config, ci }
    }

    /// Kotlin namespace-object name. Uppercases the first character only,
    /// leaving the rest untouched (e.g. `primitive_arrays` →
    /// `Primitive_arrays`). Mirrors the behavior inherited from the
    /// pre-Askama Kotlin entrypoint; a more Kotlin-idiomatic camel-case
    /// conversion can be introduced later with a visible snapshot diff.
    pub fn namespace_class_name(&self) -> String {
        let mut chars = self.ci.namespace().chars();
        match chars.next() {
            Some(c) => c.to_uppercase().chain(chars).collect(),
            None => String::new(),
        }
    }
}

/// Generate Kotlin bindings as a single string. Split by `split_and_write`
/// into individual `.kt` files via the `// UNIFFI:FILE` markers emitted by
/// the templates.
pub fn generate_bindings(config: &Config, ci: &ComponentInterface) -> Result<String> {
    KotlinWrapper::new(config.clone(), ci)
        .render()
        .context("failed to render Kotlin bindings")
}
