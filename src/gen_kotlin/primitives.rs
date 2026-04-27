/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! `CodeType` impls for Kotlin primitive + String + ByteArray types.
//!
//! Mirrors `gen_java/primitives.rs` but emits Kotlin syntax: capitalized
//! primitives (`Long` rather than `long`) and `ByteArray`/`String` for the
//! bytes/string types. Used by `gen_kotlin::filters::type_name` /
//! `lift_fn` / `lower_fn` / `primitive_call_suffix` to render namespace
//! function signatures and call-site converter wiring.

use super::{CodeType, Config};
use paste::paste;
use uniffi_bindgen::interface::ComponentInterface;

// Primitive type labels are fully-qualified (`kotlin.String`,
// `kotlin.Int`, etc) to avoid name shadowing inside sealed class
// variant declarations. A non-flat enum variant named `String` (e.g.
// `MixedEnum.String(s: String)`) would otherwise be parsed by Kotlin
// as `s: MixedEnum.String` (the variant) instead of `s: kotlin.String`
// (the built-in). Mirrors upstream uniffi-rs's
// `format!("kotlin.{class_name}")` choice.
macro_rules! impl_code_type_for_primitive {
    ($T:ident, $type_label:literal, $canonical_name:literal, $primitive_label:literal) => {
        paste! {
            #[derive(Debug)]
            pub struct $T;

            impl CodeType for $T {
                fn type_label(&self, _ci: &ComponentInterface, _config: &Config) -> String {
                    concat!("kotlin.", $type_label).into()
                }

                fn type_label_primitive(&self) -> Option<String> {
                    Some($primitive_label.into())
                }

                fn canonical_name(&self) -> String {
                    $canonical_name.into()
                }
            }
        }
    };
}

#[derive(Debug)]
pub struct BytesCodeType;
impl CodeType for BytesCodeType {
    fn type_label(&self, _ci: &ComponentInterface, _config: &Config) -> String {
        "kotlin.ByteArray".to_string()
    }

    fn canonical_name(&self) -> String {
        "ByteArray".to_string()
    }
}

#[derive(Debug)]
pub struct StringCodeType;
impl CodeType for StringCodeType {
    fn type_label(&self, _ci: &ComponentInterface, _config: &Config) -> String {
        "kotlin.String".to_string()
    }

    fn canonical_name(&self) -> String {
        "String".to_string()
    }
}

// `type_label_primitive` returns the Kotlin primitive name. The
// `primitive_call_suffix` filter only matches Byte/Short/Int/Long/Float/
// Double — Boolean is excluded because the FFI-side primitive is `Byte`,
// so the call goes through `FfiConverterBoolean.lift` rather than the
// primitive-specialized `uniffiRustCallBoolean*` helper.
impl_code_type_for_primitive!(BooleanCodeType, "Boolean", "Boolean", "Boolean");
impl_code_type_for_primitive!(Int8CodeType, "Byte", "Byte", "Byte");
impl_code_type_for_primitive!(Int16CodeType, "Short", "Short", "Short");
impl_code_type_for_primitive!(Int32CodeType, "Int", "Int", "Int");
impl_code_type_for_primitive!(Int64CodeType, "Long", "Long", "Long");
impl_code_type_for_primitive!(Float32CodeType, "Float", "Float", "Float");
impl_code_type_for_primitive!(Float64CodeType, "Double", "Double", "Double");
