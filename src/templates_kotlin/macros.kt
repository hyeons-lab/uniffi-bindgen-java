{#
// Reusable Askama macros for the Kotlin backend.
//
// P3e scope: top-level (namespace) function rendering only — no
// `self_type` (object methods), no `is_async` (suspend), no typed
// `throws_type` mapping. Functions with a `throws_type` still compile
// and run for the success path; on a Rust CALL_ERROR they raise an
// `InternalException` via `UniffiNullRustCallStatusErrorHandler`.
// Typed-error wiring lands with P3g (flat enums + error mapping).
//
// Mirrors the relevant slices of `src/templates/macros.java`. The
// surrounding patterns (whitespace control, `{% match %}` arms on
// `return_type`, `arg_list_lowered` for FfiConverter routing) match
// the Java version so future cross-backend changes can be applied
// in lockstep.
#}

{#
// Render a top-level Kotlin `fun ...` declaration that calls into a
// `UniffiLib` MethodHandle wrapper. Branches on return type:
//   - primitive (Long/Int/...): use `uniffiRustCall<Suffix>` directly,
//     no FfiConverter
//   - non-primitive: route through the generic `uniffiRustCall { ... }`
//     and wrap the result in `FfiConverter<T>.lift(...)`
//   - void (None): `uniffiRustCallVoid`
#}
{%- macro func_decl(callable, indent) -%}
{%- match callable.return_type() -%}
{%- when Some(return_type) -%}
{%- if return_type|has_primitive_ffi_type %}
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    UniffiHelpers.uniffiRustCall{{ return_type|primitive_call_suffix }} { _, _status ->
{{ indent }}        UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, false) %})
{{ indent }}    }
{%- else %}
{%- let ret_ffi_type = return_type|ffi_type %}
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    {{ return_type|lift_fn }}(
{{ indent }}        UniffiHelpers.uniffiRustCall { _allocator, _status ->
{{ indent }}            UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, ret_ffi_type.borrow()|ffi_type_is_struct) %})
{{ indent }}        }
{{ indent }}    )
{%- endif %}
{%- when None %}
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}) {
{{ indent }}    UniffiHelpers.uniffiRustCallVoid { _, _status ->
{{ indent }}        UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, false) %})
{{ indent }}    }
{{ indent }}}
{%- endmatch %}
{%- endmacro -%}

{#
// Argument list as it appears in the Kotlin function signature:
//   `name: Type, name2: Type2`.
// Uses the high-level `type_name` filter, not the FFI type — these are
// the user-facing parameters.
#}
{%- macro arg_list(callable) -%}
{%- for arg in callable.arguments() -%}
{{ arg.name()|var_name }}: {{ arg|type_name(ci, config) }}
{%- if !loop.last %}, {% endif -%}
{%- endfor -%}
{%- endmacro -%}

{#
// Argument list lowered to FFI representation. Primitive args are
// passed through unchanged (Kotlin Long ≡ FFI Int64); non-primitive
// args go through `FfiConverter<T>.lower(...)`.
#}
{%- macro arg_list_lowered(callable) -%}
{%- for arg in callable.arguments() -%}
{%- if arg|has_primitive_ffi_type -%}
{{ arg.name()|var_name }}
{%- else -%}
{{ arg|lower_fn }}({{ arg.name()|var_name }})
{%- endif -%}
{%- if !loop.last %}, {% endif -%}
{%- endfor -%}
{%- endmacro -%}

{#
// Argument list as passed to the `UniffiLib.<fn>(...)` MethodHandle
// wrapper: optional leading `_allocator` (for struct returns), the
// lowered user args, then the trailing `_status` segment.
#}
{%- macro call_args(callable, needs_allocator) -%}
{%- if needs_allocator %}_allocator, {% endif -%}
{%- call arg_list_lowered(callable) -%}
{%- if !callable.arguments().is_empty() %}, {% endif -%}_status
{%- endmacro -%}
