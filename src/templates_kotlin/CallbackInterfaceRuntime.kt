
// UNIFFI:FILE FfiConverterCallbackInterface.kt
package {{ config.package_name() }}

// Base FfiConverter for callback interfaces (foreign-implemented
// traits that Rust invokes via the vtable). Handles route through
// `UniffiHandleMap` — Rust never owns these objects, just references
// them by odd handle. Mirrors `src/templates/CallbackInterfaceRuntime.java`.
internal abstract class FfiConverterCallbackInterface<CallbackInterface : Any>
    : FfiConverter<CallbackInterface, Long> {

    @JvmField internal val handleMap: UniffiHandleMap<CallbackInterface> = UniffiHandleMap()

    fun drop(handle: Long) {
        handleMap.remove(handle)
    }

    override fun lift(value: Long): CallbackInterface = handleMap.get(value)

    override fun read(buf: java.nio.ByteBuffer): CallbackInterface = lift(buf.getLong())

    override fun lower(value: CallbackInterface): Long = handleMap.insert(value)

    override fun allocationSize(value: CallbackInterface): Long = 8L

    override fun write(value: CallbackInterface, buf: java.nio.ByteBuffer) {
        buf.putLong(lower(value))
    }
}
