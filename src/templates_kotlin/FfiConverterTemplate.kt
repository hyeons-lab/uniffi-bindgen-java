
// UNIFFI:FILE FfiConverter.kt
package {{ config.package_name() }}

// The FfiConverter interface handles converter types to and from the FFI.
//
// All implementations are public to support external types — when a type is
// external we need to import its FfiConverter.
interface FfiConverter<T, FfiType> {
    // Convert an FFI type to a Kotlin type
    fun lift(value: FfiType): T

    // Convert a Kotlin type to an FFI type
    fun lower(value: T): FfiType

    // Read a Kotlin type from a `ByteBuffer`
    fun read(buf: java.nio.ByteBuffer): T

    // Calculate bytes to allocate when creating a `RustBuffer`.
    //
    // Must return at least as many bytes as `write()` will write. May return
    // more than needed (e.g. for Strings, whose UTF-8 byte count depends on
    // the codepoints, we pessimistically allocate 3 bytes per char). Extra
    // bytes aren't a problem because the RustBuffer is short-lived.
    fun allocationSize(value: T): Long

    // Write a Kotlin type to a `ByteBuffer`
    fun write(value: T, buf: java.nio.ByteBuffer)

    // Lower a value into a `RustBuffer`.
    //
    // This lowers a value into a `RustBuffer` rather than the normal FfiType.
    // Used by the callback interface code — callback returns are always
    // serialized into a `RustBuffer` regardless of their normal FFI type.
    fun lowerIntoRustBuffer(value: T): java.lang.foreign.MemorySegment {
        val rbuf = RustBuffer.alloc(allocationSize(value))
        try {
            val bbuf = RustBuffer.asWriteByteBuffer(rbuf)
            write(value, bbuf)
            RustBuffer.setLen(rbuf, bbuf.position().toLong())
            return rbuf
        } catch (e: Throwable) {
            RustBuffer.free(rbuf)
            throw e
        }
    }

    // Lift a value from a `RustBuffer`.
    //
    // Mostly here for symmetry with `lowerIntoRustBuffer()`. Currently only
    // used by `FfiConverterRustBuffer` below.
    fun liftFromRustBuffer(rbuf: java.lang.foreign.MemorySegment): T {
        val byteBuf = RustBuffer.asByteBuffer(rbuf)
        try {
            val item = read(byteBuf)
            if (byteBuf.hasRemaining()) {
                throw RuntimeException("junk remaining in buffer after lifting, something is very wrong!!")
            }
            return item
        } finally {
            RustBuffer.free(rbuf)
        }
    }
}

// UNIFFI:FILE FfiConverterRustBuffer.kt
package {{ config.package_name() }}

// FfiConverter that uses `RustBuffer` as the FfiType.
interface FfiConverterRustBuffer<T> : FfiConverter<T, java.lang.foreign.MemorySegment> {
    override fun lift(value: java.lang.foreign.MemorySegment): T = liftFromRustBuffer(value)
    override fun lower(value: T): java.lang.foreign.MemorySegment = lowerIntoRustBuffer(value)
}
