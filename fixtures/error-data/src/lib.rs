/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Minimal canary for P3l-errors codegen: associated-data error
//! types (Rust enum variants carrying fields, exposed via uniffi as
//! `[Error] interface MyError { ... }`). Two variants — one with a
//! single `String` field, one with multiple fields — to exercise
//! both single- and multi-field nested data classes in the
//! generated `sealed class` hierarchy.

uniffi::setup_scaffolding!("error_data");

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ApiError {
    #[error("network error: {reason}")]
    Network { reason: String },

    #[error("server returned {code}: {reason}")]
    Server { code: i32, reason: String },
}

#[uniffi::export]
fn try_call(network_failure: bool) -> Result<String, ApiError> {
    if network_failure {
        Err(ApiError::Network {
            reason: "timeout".to_string(),
        })
    } else {
        Err(ApiError::Server {
            code: 500,
            reason: "internal server error".to_string(),
        })
    }
}
