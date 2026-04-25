{#
// Reusable Askama macros for the Kotlin backend.
//
// P3i scope: top-level (namespace) functions, object instance methods
// (self_type = Type::Object), and named object constructors
// (companion-object factory functions). Typed errors are handled
// everywhere via the shared `rust_call_prefix` helper.
//
// Still deferred: async methods (suspend fns + CompletableFuture-free
// coroutine bridge), trait/callback-interface `self_type`, object
// method self-type being anything other than `Type::Object` (byref
// Record/Enum self — unusual; not exercised by sprites/coverall).
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
// Render a Kotlin `fun …` declaration that calls into a `UniffiLib`
// MethodHandle wrapper. Branches on:
//   * `self_type()` — instance methods on `Type::Object` wrap the
//     FFI call in `callWithHandle { uniffiHandle -> … }`, prepending
//     `uniffiHandle` as the first Rust arg; namespace functions
//     (self_type = None) call the FFI directly.
//   * `return_type()` — primitive / non-primitive / void return.
//   * `throws_type()` — emits `@Throws(<T>::class)` and routes
//     through `uniffiRustCallWithError<Suffix>(<T>ErrorHandler())`.
#}
{%- macro func_decl(callable, indent) -%}
{% call func_decl_inner(callable, indent, false) %}
{%- endmacro -%}

{#
// Variant of `func_decl` that prepends `override` to every emitted
// `fun ...` declaration. Used by `[Trait, WithForeign]` impl classes
// where each method satisfies the corresponding interface contract.
#}
{%- macro override_func_decl(callable, indent) -%}
{% call func_decl_inner(callable, indent, true) %}
{%- endmacro -%}

{%- macro func_decl_inner(callable, indent, is_override) -%}
{%- match callable.throws_type() %}
{%- when Some(error_type) %}
{{ indent }}@Throws({{ error_type|type_name(ci, config) }}::class)
{%- when None %}
{%- endmatch %}
{%- let is_method = callable.self_type().is_some() -%}
{%- match callable.return_type() -%}
{%- when Some(return_type) -%}
{%- if return_type|has_primitive_ffi_type %}
{%- if is_method %}
{{ indent }}{% if is_override %}override {% endif %}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    callWithHandle { uniffiHandle ->
{{ indent }}        {% call rust_call_prefix(callable) %} { _, _status ->
{{ indent }}            UniffiLib.{{ callable.ffi_func().name() }}({% call method_call_args(callable, false) %})
{{ indent }}        }
{{ indent }}    }
{%- else %}
{{ indent }}{% if is_override %}override {% endif %}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    {% call rust_call_prefix(callable) %} { _, _status ->
{{ indent }}        UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, false) %})
{{ indent }}    }
{%- endif %}
{%- else %}
{%- let ret_ffi_type = return_type|ffi_type %}
{%- if is_method %}
{{ indent }}{% if is_override %}override {% endif %}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    {{ return_type|lift_fn }}(
{{ indent }}        callWithHandle { uniffiHandle ->
{{ indent }}            {% call rust_call_prefix(callable) %} { _allocator, _status ->
{{ indent }}                UniffiLib.{{ callable.ffi_func().name() }}({% call method_call_args(callable, ret_ffi_type.borrow()|ffi_type_is_struct) %})
{{ indent }}            }
{{ indent }}        }
{{ indent }}    )
{%- else %}
{{ indent }}{% if is_override %}override {% endif %}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ return_type|type_name(ci, config) }} =
{{ indent }}    {{ return_type|lift_fn }}(
{{ indent }}        {% call rust_call_prefix(callable) %} { _allocator, _status ->
{{ indent }}            UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, ret_ffi_type.borrow()|ffi_type_is_struct) %})
{{ indent }}        }
{{ indent }}    )
{%- endif %}
{%- endif %}
{%- when None %}
{%- if is_method %}
{{ indent }}{% if is_override %}override {% endif %}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}) {
{{ indent }}    callWithHandle { uniffiHandle ->
{{ indent }}        {% call rust_call_prefix(callable) %} { _, _status ->
{{ indent }}            UniffiLib.{{ callable.ffi_func().name() }}({% call method_call_args(callable, false) %})
{{ indent }}        }
{{ indent }}    }
{{ indent }}}
{%- else %}
{{ indent }}{% if is_override %}override {% endif %}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}) {
{{ indent }}    {% call rust_call_prefix(callable) %} { _, _status ->
{{ indent }}        UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, false) %})
{{ indent }}    }
{{ indent }}}
{%- endif %}
{%- endmatch %}
{%- endmacro -%}

{#
// Render a named constructor as a `companion object` factory function
// that returns the enclosing object type. Mirrors the Java "static
// factory" shape for `[Name=foo]` alternate constructors, adapted to
// Kotlin idioms: `@JvmStatic` so Java consumers see it as a static
// method, primary constructor delegation via
// `<Type>(UniffiWithHandle, <ffi-call>)`.
#}
{%- macro named_constructor_decl(type_name, callable, indent) -%}
{%- match callable.throws_type() %}
{%- when Some(error_type) %}
{{ indent }}@Throws({{ error_type|type_name(ci, config) }}::class)
{%- when None %}
{%- endmatch %}
{{ indent }}@JvmStatic
{{ indent }}fun {{ callable.name()|fn_name }}({% call arg_list(callable) %}): {{ type_name }} =
{{ indent }}    {{ type_name }}(
{{ indent }}        UniffiWithHandle,
{{ indent }}        {% call rust_call_prefix(callable) %} { _allocator, _status ->
{{ indent }}            UniffiLib.{{ callable.ffi_func().name() }}({% call call_args(callable, false) %})
{{ indent }}        },
{{ indent }}    )
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

{#
// Method variant of `call_args`: prepends `uniffiHandle` as the first
// Rust arg. Used by instance-method rendering inside the
// `callWithHandle { uniffiHandle -> … }` wrapper.
#}
{%- macro method_call_args(callable, needs_allocator) -%}
{%- if needs_allocator %}_allocator, {% endif -%}
uniffiHandle
{%- if !callable.arguments().is_empty() %}, {% call arg_list_lowered(callable) %}{% endif -%}
, _status
{%- endmacro -%}
