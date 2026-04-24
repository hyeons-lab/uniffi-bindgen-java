{#
// Reusable Askama macros for the Kotlin backend.
//
// P3g scope: top-level (namespace) function rendering with typed-error
// support — no `self_type` (object methods), no `is_async` (suspend).
// Functions with a `throws_type` emit `@Throws(<Name>Exception::class)`
// and route through `uniffiRustCallWithError<Suffix>(<Name>ErrorHandler(),
// ...)`; error-free functions stay on the plain `uniffiRustCall<Suffix>`
// path.
//
// Mirrors the relevant slices of `src/templates/macros.java`. The
// surrounding patterns (whitespace control, `{% match %}` arms on
// `return_type`, `arg_list_lowered` for FfiConverter routing) match
// the Java version so future cross-backend changes can be applied
// in lockstep.
#}

{#
// Expands to `<helper>(<error-args>)`, the prefix that precedes the
// trailing `{ _, _status -> ... }` lambda at a Rust-call site. Four
// shapes:
//   - throws + return:     UniffiHelpers.uniffiRustCallWithError<Suffix>(<E>ErrorHandler())
//   - throws + void:       UniffiHelpers.uniffiRustCallVoidWithError(<E>ErrorHandler())
//   - no throws + return:  UniffiHelpers.uniffiRustCall<Suffix>()
//   - no throws + void:    UniffiHelpers.uniffiRustCallVoid()
// The `uniffiRustCall*` family all accept a trailing lambda, so the
// caller appends ` { _, _status -> body }` uniformly regardless of
// which shape this macro emits. `<Suffix>` is the primitive
// specialization (`Long`, `Int`, ...) or empty for non-primitive
// / boxed returns.
#}
{%- macro rust_call_prefix(callable) -%}
UniffiHelpers.
{%- match callable.return_type() -%}
{%- when Some(return_type) -%}
uniffiRustCall{% if callable.throws_type().is_some() %}WithError{% endif %}{{ return_type|primitive_call_suffix }}
{%- when None -%}
uniffiRustCallVoid{% if callable.throws_type().is_some() %}WithError{% endif %}
{%- endmatch -%}
{%- match callable.throws_type() -%}
{%- when Some(error_type) -%}
({{ error_type|type_name(ci, config) }}ErrorHandler())
{%- when None -%}
{%- endmatch -%}
{%- endmacro -%}

{#
// Render a top-level Kotlin `fun ...` declaration that calls into a
// `UniffiLib` MethodHandle wrapper. Branches on return type (primitive
// / non-primitive / void). Functions with a `throws_type` also emit a
// leading `@Throws(<T>::class)` annotation so Java consumers see a
// checked-exception signature.
#}
{%- macro func_decl(callable, indent) -%}
{%- match callable.throws_type() %}
{%- when Some(error_type) %}
{{ indent }}@Throws({{ error_type|type_name(ci, config) }}::class)
{%- when None %}
{%- endmatch %}
{%- match callable.return_type() -%}
{%- when Some(return_type) -%}
{%- if return_type|has_primitive_ffi_type %}
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    {% call rust_call_prefix(callable) %} { _, _status ->
{{ indent }}        UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, false) %})
{{ indent }}    }
{%- else %}
{%- let ret_ffi_type = return_type|ffi_type %}
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    {{ return_type|lift_fn }}(
{{ indent }}        {% call rust_call_prefix(callable) %} { _allocator, _status ->
{{ indent }}            UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, ret_ffi_type.borrow()|ffi_type_is_struct) %})
{{ indent }}        }
{{ indent }}    )
{%- endif %}
{%- when None %}
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}) {
{{ indent }}    {% call rust_call_prefix(callable) %} { _, _status ->
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
