/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Shared codegen scaffolding usable by every language backend.
//!
//! This module exists so that future language backends (starting with the
//! planned Kotlin backend) can reuse what is genuinely language-neutral
//! instead of copy-pasting from `gen_java/`. P1.3 seeds it with the obvious
//! shared pieces; subsequent phases will pull more in as the Kotlin backend
//! reveals concrete duplication.
//!
//! Guiding principle: only hoist items here once a second backend would
//! want them. Premature abstraction on a one-backend codebase just burns
//! indirection budget with no upside.

mod config;

pub use config::{CustomTypeConfig, ExternalPackageResolver, potentially_add_external_package};
