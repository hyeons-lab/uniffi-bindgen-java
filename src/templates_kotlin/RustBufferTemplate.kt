
// UNIFFI:FILE RustBuffer.kt
package {{ config.package_name() }}

/**
 * Helper for safely working with byte buffers returned from the Rust code.
 * A rust-owned buffer is represented by its capacity, its current length,
 * and a pointer to the underlying data.
 */
object RustBuffer {
    val LAYOUT: java.lang.foreign.StructLayout = java.lang.foreign.MemoryLayout.structLayout(
        java.lang.foreign.ValueLayout.JAVA_LONG.withName("capacity"),
        java.lang.foreign.ValueLayout.JAVA_LONG.withName("len"),
        java.lang.foreign.ValueLayout.ADDRESS.withName("data"),
    )

    private val OFFSET_CAPACITY: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("capacity"))
    private val OFFSET_LEN: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("len"))
    private val OFFSET_DATA: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("data"))

    fun getCapacity(seg: java.lang.foreign.MemorySegment): Long =
        seg.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_CAPACITY)

    fun setCapacity(seg: java.lang.foreign.MemorySegment, value: Long) {
        seg.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_CAPACITY, value)
    }

    fun getLen(seg: java.lang.foreign.MemorySegment): Long =
        seg.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_LEN)

    fun setLen(seg: java.lang.foreign.MemorySegment, value: Long) {
        seg.set(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, OFFSET_LEN, value)
    }

    fun getData(seg: java.lang.foreign.MemorySegment): java.lang.foreign.MemorySegment =
        seg.get(java.lang.foreign.ValueLayout.ADDRESS_UNALIGNED, OFFSET_DATA)

    fun setData(seg: java.lang.foreign.MemorySegment, value: java.lang.foreign.MemorySegment) {
        seg.set(java.lang.foreign.ValueLayout.ADDRESS_UNALIGNED, OFFSET_DATA, value)
    }

    fun alloc(size: Long): java.lang.foreign.MemorySegment {
        val buffer = UniffiHelpers.uniffiRustCall { allocator, status ->
            UniffiLib.{{ ci.ffi_rustbuffer_alloc().name() }}(allocator, size, status)
        }
        if (getData(buffer) == java.lang.foreign.MemorySegment.NULL && size > 0) {
            throw RuntimeException("RustBuffer.alloc() returned null data pointer (size=$size)")
        }
        return buffer
    }

    fun free(buffer: java.lang.foreign.MemorySegment) {
        UniffiHelpers.uniffiRustCallVoid { _, status ->
            UniffiLib.{{ ci.ffi_rustbuffer_free().name() }}(buffer, status)
        }
    }

    /** Get a ByteBuffer view of the data for reading (len bytes). */
    fun asByteBuffer(seg: java.lang.foreign.MemorySegment): java.nio.ByteBuffer {
        val len = getLen(seg)
        if (len == 0L) {
            return java.nio.ByteBuffer.allocate(0).order(java.nio.ByteOrder.BIG_ENDIAN)
        }
        return getData(seg).reinterpret(len).asByteBuffer().order(java.nio.ByteOrder.BIG_ENDIAN)
    }

    /** Get a ByteBuffer view of the data for writing (capacity bytes). */
    fun asWriteByteBuffer(seg: java.lang.foreign.MemorySegment): java.nio.ByteBuffer {
        val capacity = getCapacity(seg)
        if (capacity == 0L) {
            return java.nio.ByteBuffer.allocate(0).order(java.nio.ByteOrder.BIG_ENDIAN)
        }
        return getData(seg).reinterpret(capacity).asByteBuffer().order(java.nio.ByteOrder.BIG_ENDIAN)
    }
}

// UNIFFI:FILE ForeignBytes.kt
package {{ config.package_name() }}

// Helper for safely passing byte references into the rust code.
//
// Not actually used yet — there aren't many things you can take a direct
// pointer to in the JVM, and if we're going to copy something we might as
// well copy it into a `RustBuffer`. Here for API completeness.
object ForeignBytes {
    val LAYOUT: java.lang.foreign.StructLayout = java.lang.foreign.MemoryLayout.structLayout(
        java.lang.foreign.ValueLayout.JAVA_INT.withName("len"),
        java.lang.foreign.MemoryLayout.paddingLayout(4),  // alignment padding before ADDRESS
        java.lang.foreign.ValueLayout.ADDRESS.withName("data"),
    )

    private val OFFSET_LEN: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("len"))
    private val OFFSET_DATA: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("data"))

    fun getLen(seg: java.lang.foreign.MemorySegment): Int =
        seg.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, OFFSET_LEN)

    fun setLen(seg: java.lang.foreign.MemorySegment, value: Int) {
        seg.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, OFFSET_LEN, value)
    }

    fun getData(seg: java.lang.foreign.MemorySegment): java.lang.foreign.MemorySegment =
        seg.get(java.lang.foreign.ValueLayout.ADDRESS_UNALIGNED, OFFSET_DATA)

    fun setData(seg: java.lang.foreign.MemorySegment, value: java.lang.foreign.MemorySegment) {
        seg.set(java.lang.foreign.ValueLayout.ADDRESS_UNALIGNED, OFFSET_DATA, value)
    }
}
