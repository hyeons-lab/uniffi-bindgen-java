
{%- let obj = ci.get_object_definition(name).unwrap() %}
{%- let methods = obj.methods() %}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

// Object wrapper over a Rust `Arc<T>`. Uses a handle-based protocol
// for cross-FFI lifecycle: the `AtomicLong` call counter + `AtomicBoolean`
// closed flag together implement an in-flight-call guard so close()
// and concurrent method calls can't race into a use-after-free on the
// Rust side.
//
// Structure mirrors `src/templates/ObjectTemplate.java` verbatim at
// the protocol level — same handshake, same Cleaner integration.
// Kotlin-only differences:
//   * `object UniffiWithHandle` disambiguates the handle-wrapping
//     primary constructor from user constructors without Java's
//     marker-class-with-private-ctor dance.
//   * `AutoCloseable` + `close()` so consumers can use
//     `obj.use { ... }` (Kotlin's try-with-resources) idiomatically.
//   * Named constructors (`[Name=foo]`) render as `companion object`
//     factory functions rather than Java static methods.
class {{ type_name }} internal constructor(
    @Suppress("UNUSED_PARAMETER") phantom: UniffiWithHandle,
    internal val handle: Long,
) : AutoCloseable {
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
    // Async constructors are not supported in this revision of the
    // Kotlin backend. The primary constructor for `{{ type_name }}` is
    // `async` in the UDL, so it's omitted here; use a named constructor
    // (if any) or wait for the async Kotlin phase to land.
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
     */
    inline fun <R> callWithHandle(block: (Long) -> R): R {
        // Check and increment the call counter. Retries on CAS failure
        // under concurrent updates.
        var c: Long
        do {
            c = callCounter.get()
            check(c != 0L) { "{{ type_name }} object has already been destroyed" }
            check(c != Long.MAX_VALUE) { "{{ type_name }} call counter would overflow" }
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
    {% call kotlin::func_decl(meth, "    ") %}
    {% endfor %}

    {%- if !obj.alternate_constructors().is_empty() %}
    companion object {
        {% for cons in obj.alternate_constructors() -%}
        {%- if cons.is_async() %}
        // Async named constructor `{{ cons.name() }}` skipped — async
        // unsupported in this revision of the Kotlin backend.
        {%- else %}
        {% call kotlin::named_constructor_decl(type_name, cons, "        ") %}
        {%- endif %}
        {% endfor %}
    }
    {%- endif %}
}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

// FfiConverter routes `{{ type_name }}` across the FFI as an 8-byte
// handle. No callback-interface support in this template revision —
// P3j adds the LSB-tagged handle dispatch for trait interfaces.
object {{ ffi_converter_name }} : FfiConverter<{{ type_name }}, Long> {
    override fun lower(value: {{ type_name }}): Long = value.uniffiCloneHandle()

    override fun lift(value: Long): {{ type_name }} =
        {{ type_name }}(UniffiWithHandle, value)

    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} = lift(buf.getLong())

    override fun allocationSize(value: {{ type_name }}): Long = 8L

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        buf.putLong(lower(value))
    }
}
