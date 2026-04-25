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
// Property name for an enum variant's field, falling back to `v<N>`
// when the field is positional (Rust tuple-variant `Dog(Arc<Foo>)`
// → field name = "", index 0 → `v1`). Mirrors the Java backend's
// `field_name` macro. `field_num` is 1-indexed (askama loop.index
// starts at 1) to match Rust's tuple positional convention in
// uniffi metadata.
#}
{%- macro field_name(field, field_num) -%}
{%- if field.name().is_empty() -%}
v{{ field_num }}
{%- else -%}
{{ field.name()|var_name }}
{%- endif -%}
{%- endmacro -%}

{#
// Same as `field_name` but strips the backticks that `var_name`
// adds for Kotlin reserved words. Use this when rendering a name as
// human-readable text (e.g. the `field=value` label inside the
// synthetic message attached to a non-flat error variant). Without
// it, a Rust field named `object` would surface in the exception
// message as `` `object`=… ``, leaking the backtick escape into
// user-visible output. Mirrors Java's `field_name_unquoted`.
#}
{%- macro field_name_unquoted(field, field_num) -%}
{%- if field.name().is_empty() -%}
v{{ field_num }}
{%- else -%}
{{ field.name()|var_name|unquote }}
{%- endif -%}
{%- endmacro -%}

{#
// Macro for `#[uniffi::export(Display, Eq, Ord, Hash)]` proc-macro
// trait method overrides on Records and Objects. Mirrors Java's
// `uniffi_trait_impls` shape but uses Kotlin idioms — single-expression
// bodies, smart-cast `is` checks, and `.toInt()` for the Int-returning
// `hashCode` / `compareTo` overrides (Rust returns u64 / i8).
//
// Each FFI call routes through `trait_ffi_call` which dispatches on the
// trait method's `self_type()`:
//   - Object self → wrap in `callWithHandle { uniffiHandle -> ... }`
//   - Record self → call `lower(this)` directly (no handle protocol)
//
// Display takes precedence over Debug for `toString()` — same priority
// as the Java backend.
#}
{%- macro uniffi_trait_impls(uniffi_trait_methods) %}
{%- if let Some(fmt) = uniffi_trait_methods.display_fmt.as_ref().or(uniffi_trait_methods.debug_fmt.as_ref()) %}

    override fun toString(): String =
        {{ fmt.return_type().unwrap()|lift_fn }}({% call trait_ffi_call(fmt) %})
{%- endif %}
{%- if let Some(eq) = uniffi_trait_methods.eq_eq.as_ref() %}

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is {{ eq.object_name()|class_name(ci) }}) return false
        return {{ eq.return_type().unwrap()|lift_fn }}({% call trait_ffi_call(eq) %})
    }
{%- endif %}
{%- if let Some(hash) = uniffi_trait_methods.hash_hash.as_ref() %}

    override fun hashCode(): Int =
        {{ hash.return_type().unwrap()|lift_fn }}({% call trait_ffi_call(hash) %}).toInt()
{%- endif %}
{%- if let Some(cmp) = uniffi_trait_methods.ord_cmp.as_ref() %}

    override fun compareTo(other: {{ cmp.object_name()|class_name(ci) }}): Int =
        {{ cmp.return_type().unwrap()|lift_fn }}({% call trait_ffi_call(cmp) %}).toInt()
{%- endif %}
{%- endmacro %}

{#
// Inner FFI-call wrapper for `uniffi_trait_impls`. Branches on the
// trait method's `self_type()`:
//   - `Type::Object` self → `callWithHandle { uniffiHandle -> ... }`
//     (matches the existing Object-method protocol).
//   - Other self (Record / Enum) → `lower(this)` directly as the first
//     FFI argument.
// The trailing `arg_list_lowered` covers the second arg for `eq_eq` /
// `ord_cmp` (uniffi names it `other`, which lines up with the Kotlin
// override's parameter name post smart-cast).
//
// Allocator handling: when the FFI return type is a struct
// (`RustBuffer`, e.g. for the `Display` → `String` trait method),
// `UniffiLib.<fn>` expects an allocator as its first parameter.
// Mirrors the `ffi_type.borrow()|ffi_type_is_struct` check used by
// `func_decl_inner` so the same allocator-prepend rule applies here.
#}
{%- macro trait_ffi_call(func) -%}
{%- match func.self_type() -%}
{%- when Some with (Type::Object { .. }) -%}
callWithHandle { uniffiHandle ->
            UniffiHelpers.uniffiRustCall { _allocator, _status ->
                UniffiLib.{{ func.ffi_func().name() }}(
                    {%- match func.return_type() -%}
                    {%- when Some with (return_type) -%}
                    {%- let ret_ffi_type = return_type|ffi_type -%}
                    {%- if ret_ffi_type.borrow()|ffi_type_is_struct %}_allocator, {% endif -%}
                    {%- when None -%}
                    {%- endmatch -%}
                    uniffiHandle{% if !func.arguments().is_empty() %}, {% call arg_list_lowered(func) %}{% endif %}, _status)
            }
        }
{%- when Some with (t) -%}
UniffiHelpers.uniffiRustCall { _allocator, _status ->
            UniffiLib.{{ func.ffi_func().name() }}(
                {%- match func.return_type() -%}
                {%- when Some with (return_type) -%}
                {%- let ret_ffi_type = return_type|ffi_type -%}
                {%- if ret_ffi_type.borrow()|ffi_type_is_struct %}_allocator, {% endif -%}
                {%- when None -%}
                {%- endmatch -%}
                {{ t|lower_fn }}(this){% if !func.arguments().is_empty() %}, {% call arg_list_lowered(func) %}{% endif %}, _status)
        }
{%- when None -%}
{%- endmatch -%}
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
