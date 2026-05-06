
{%- let obj = ci.get_object_definition(name).unwrap() %}
{%- let methods = obj.methods() %}
{%- let (interface_name, impl_class_name) = obj|object_names(ci) %}
{%- let uniffi_trait_methods = obj.uniffi_trait_methods() %}

{#- Always emit a method-signature interface alongside the concrete
   wrapper so consumers can substitute test doubles or alternative
   impls. For `[Trait, WithForeign]` objects this interface is also
   the user-facing trait declaration that foreign Kotlin classes can
   implement directly; the FfiConverter's LSB dispatch routes between
   Rust- and foreign-implemented instances. For plain objects the
   interface is method-signature-only — lifecycle (`AutoCloseable`)
   stays on the concrete class. Mirrors the Java backend. -#}
{% include "Interface.kt" %}

// UNIFFI:FILE {{ impl_class_name }}.kt
package {{ config.package_name() }}

// Object wrapper over a Rust `Arc<T>`. Uses a handle-based protocol
// for cross-FFI lifecycle: the `AtomicLong` call counter + `AtomicBoolean`
// closed flag together implement an in-flight-call guard so close()
// and concurrent method calls can't race into a use-after-free on the
// Rust side.
//
// Structure mirrors `src/templates/ObjectTemplate.java` verbatim at
// the protocol level — same handshake, same Cleaner integration.
// Every Object implements its sibling `<Name>Interface` (method
// signatures only, no `AutoCloseable`); for `[Trait, WithForeign]`
// objects this same interface is the user-facing trait declaration
// that foreign Kotlin classes can implement directly. Methods are
// emitted with `override` in both cases.
class {{ impl_class_name }} internal constructor(
    @Suppress("UNUSED_PARAMETER") phantom: UniffiWithHandle,
    internal val handle: Long,
) : AutoCloseable, {{ interface_name }}{% if uniffi_trait_methods.ord_cmp.is_some() %}, Comparable<{{ impl_class_name }}>{% endif %} {
    private val wasDestroyed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val callCounter = java.util.concurrent.atomic.AtomicLong(1L)
    // NoHandle wrappers (handle == 0) don't register a cleaner: there's
    // no Rust peer to free, and registering would just queue a dead
    // Cleanable. Real wrappers register; `cleanable` is nullable so
    // `close()` can tolerate either shape.
    private val cleanable: UniffiCleaner.Cleanable? =
        if (handle != 0L) UniffiLib.CLEANER.register(this, UniffiCleanAction(handle)) else null

    /**
     * Test-only constructor for instantiating a fake wrapper with no
     * connected Rust peer. Any method call on a fake object will fail.
     */
    constructor(@Suppress("UNUSED_PARAMETER") noHandle: NoHandle) : this(UniffiWithHandle, 0L)

    {%- match obj.primary_constructor() %}
    {%- when Some(cons) %}
    {%-     if cons.is_async() %}
    // Primary constructor `{{ cons.name() }}` is `async`. Kotlin
    // constructors cannot be `suspend`, so it's emitted as a
    // `companion object` factory (`{{ impl_class_name }}.{{ cons.name()|fn_name }}(...)`)
    // below.
    {%-     else %}
    constructor({% call kotlin::arg_list(cons) %}) : this(
        UniffiWithHandle,
        {% call kotlin::rust_call_prefix(cons) %} { _allocator, _status ->
            UniffiLib.{{ cons.ffi_func().name() }}({% call kotlin::call_args(cons, false) %})
        },
    )
    {%-     endif %}
    {%- when None %}
    {%- endmatch %}

    override fun close() {
        // Single-shot. Subsequent close() calls are silent no-ops.
        if (wasDestroyed.compareAndSet(false, true)) {
            // Match the initial count of 1 given at creation time.
            if (callCounter.decrementAndGet() == 0L) {
                cleanable?.clean()
            }
        }
    }

    /**
     * Run [block] with the live Rust handle, holding the wrapper alive
     * for the duration of the call. Throws `IllegalStateException` if
     * the wrapper has already been destroyed.
     *
     * Uses a CAS retry loop on the call counter so concurrent method
     * calls can run without taking a lock; see the comment block in
     * `src/templates/ObjectTemplate.java` for the full correctness
     * argument.
     *
     * NOT `inline`: the body reads `private` fields (`callCounter`,
     * `cleanable`) and calls `internal fun uniffiCloneHandle()`. A
     * public `inline fun` cannot reference non-public-API members
     * without `@PublishedApi internal` annotations on each one;
     * leaving it as a regular method costs one closure allocation
     * per call (negligible vs the FFI roundtrip) and matches
     * upstream uniffi-rs's Kotlin shape.
     */
    fun <R> callWithHandle(block: (Long) -> R): R {
        // Check and increment the call counter. Retries on CAS failure
        // under concurrent updates.
        var c: Long
        do {
            c = callCounter.get()
            check(c != 0L) { "{{ impl_class_name }} object has already been destroyed" }
            check(c != Long.MAX_VALUE) { "{{ impl_class_name }} call counter would overflow" }
        } while (!callCounter.compareAndSet(c, c + 1L))
        try {
            return block(uniffiCloneHandle())
        } finally {
            if (callCounter.decrementAndGet() == 0L) {
                cleanable?.clean()
            }
        }
    }

    internal fun uniffiCloneHandle(): Long =
        UniffiHelpers.uniffiRustCall { _allocator, _status ->
            if (handle == 0L) {
                throw NullPointerException()
            }
            UniffiLib.{{ obj.ffi_object_clone().name() }}(handle, _status)
        }

    // IMPORTANT: NOT `inner class`. A Kotlin `inner class` would capture
    // an implicit reference to the outer wrapper, which would keep the
    // wrapper strongly reachable as long as the Cleaner holds the
    // `Runnable` alive — meaning the wrapper could never become
    // phantom-reachable, the cleaner would never fire, and every
    // forgotten `close()` would leak the Rust handle. Keeping this as a
    // nested class means the Runnable only references the primitive
    // `handle` Long it was constructed with, which is exactly what the
    // Cleaner contract requires.
    private class UniffiCleanAction(private val handle: Long) : Runnable {
        override fun run() {
            // handle == 0 is defense-in-depth: `cleanable` is only
            // registered when handle != 0, so this branch shouldn't
            // be reachable in practice. Leaving the guard in case the
            // class is ever instantiated directly (e.g. by tests).
            if (handle != 0L) {
                UniffiHelpers.uniffiRustCall { _allocator, _status ->
                    UniffiLib.{{ obj.ffi_object_free().name() }}(handle, _status)
                }
            }
        }
    }

    {% for meth in obj.methods() -%}
    {% call kotlin::override_func_decl(meth, "    ") %}
    {% endfor %}

    {#- Companion object emitted when there are alternate constructors
        OR when the primary constructor is async (and therefore
        demoted to a companion-object `suspend fun` factory because
        Kotlin constructors cannot be `suspend`). The two-arm
        structure avoids needing a Rust-side helper to compute the
        disjunction — askama only sees the inner `is_async()` check
        in the no-alternates arm. #}
    {%- if !obj.alternate_constructors().is_empty() %}
    companion object {
        {%- if let Some(cons) = obj.primary_constructor() -%}
        {%- if cons.is_async() %}
        {% call kotlin::named_constructor_decl(impl_class_name, cons, "        ") %}
        {%- endif -%}
        {%- endif %}
        {% for cons in obj.alternate_constructors() -%}
        {% call kotlin::named_constructor_decl(impl_class_name, cons, "        ") %}
        {% endfor %}
    }
    {%- else if let Some(cons) = obj.primary_constructor() %}
    {%- if cons.is_async() %}
    companion object {
        {% call kotlin::named_constructor_decl(impl_class_name, cons, "        ") %}
    }
    {%- endif %}
    {%- endif %}
    {#- `#[uniffi::export(Eq, Ord, Hash, Display, Debug)]` proc-macro
       trait overrides. Each override routes through a Rust-side FFI
       call wrapped in `callWithHandle` so the in-flight-call counter
       holds the wrapper alive across the trait method's roundtrip. -#}
{% call kotlin::uniffi_trait_impls(uniffi_trait_methods, "    ") %}
}

{%- if obj.has_callback_interface() %}
{#- Foreign-vtable assembly + per-method upcall stubs that route Rust→Kotlin
   calls into Kotlin implementations of the trait interface. Reuses the
   shared CallbackInterfaceImpl.kt template that ships with P3j-b. -#}
{%- let ffi_init_callback = obj.ffi_init_callback() %}
{%- let vtable = obj.vtable().expect("[Trait, WithForeign] object missing vtable") %}
{%- let vtable_methods = obj.vtable_methods() %}
{% include "CallbackInterfaceImpl.kt" %}
{%- endif %}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

{%- if obj.has_callback_interface() %}
// LSB-tagged FfiConverter for `[Trait, WithForeign]` objects. Rust-side
// instances are wrapped by `{{ impl_class_name }}` and carry even
// handles (LSB = 0); Kotlin-side implementations of the {{ interface_name }}
// trait get registered in the per-FfiConverter `handleMap` and carry
// odd handles (LSB = 1). `lift` / `lower` dispatch on the LSB to route
// to the right side without an extra type tag.
internal object {{ ffi_converter_name }} : FfiConverter<{{ interface_name }}, Long> {
    @JvmField internal val handleMap: UniffiHandleMap<{{ interface_name }}> = UniffiHandleMap()

    override fun lower(value: {{ interface_name }}): Long =
        if (value is {{ impl_class_name }}) {
            value.uniffiCloneHandle()
        } else {
            handleMap.insert(value)
        }

    override fun lift(value: Long): {{ interface_name }} =
        if ((value and 1L) == 0L) {
            {{ impl_class_name }}(UniffiWithHandle, value)
        } else {
            handleMap.remove(value)
        }

    override fun read(buf: java.nio.ByteBuffer): {{ interface_name }} = lift(buf.getLong())

    override fun allocationSize(value: {{ interface_name }}): Long = 8L

    override fun write(value: {{ interface_name }}, buf: java.nio.ByteBuffer) {
        buf.putLong(lower(value))
    }
}
{%- else %}
// FfiConverter routes `{{ impl_class_name }}` across the FFI as an 8-byte
// handle. No callback-interface support — pure Rust-owned object.
object {{ ffi_converter_name }} : FfiConverter<{{ impl_class_name }}, Long> {
    override fun lower(value: {{ impl_class_name }}): Long = value.uniffiCloneHandle()

    override fun lift(value: Long): {{ impl_class_name }} =
        {{ impl_class_name }}(UniffiWithHandle, value)

    override fun read(buf: java.nio.ByteBuffer): {{ impl_class_name }} = lift(buf.getLong())

    override fun allocationSize(value: {{ impl_class_name }}): Long = 8L

    override fun write(value: {{ impl_class_name }}, buf: java.nio.ByteBuffer) {
        buf.putLong(lower(value))
    }
}
{%- endif %}
