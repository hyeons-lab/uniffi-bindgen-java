
// UNIFFI:FILE UniffiRustCallStatus.kt
package {{ config.package_name() }}

object UniffiRustCallStatus {
    val LAYOUT: java.lang.foreign.StructLayout = java.lang.foreign.MemoryLayout.structLayout(
        java.lang.foreign.ValueLayout.JAVA_BYTE.withName("code"),
        java.lang.foreign.MemoryLayout.paddingLayout(7),  // 7 bytes padding for alignment before RustBuffer
        RustBuffer.LAYOUT.withName("error_buf"),
    )

    private val OFFSET_CODE: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("code"))
    private val OFFSET_ERROR_BUF: Long =
        LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("error_buf"))

    const val UNIFFI_CALL_SUCCESS: Byte = 0
    const val UNIFFI_CALL_ERROR: Byte = 1
    const val UNIFFI_CALL_UNEXPECTED_ERROR: Byte = 2

    fun getCode(seg: java.lang.foreign.MemorySegment): Byte =
        seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, OFFSET_CODE)

    fun setCode(seg: java.lang.foreign.MemorySegment, value: Byte) {
        seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, OFFSET_CODE, value)
    }

    fun getErrorBuf(seg: java.lang.foreign.MemorySegment): java.lang.foreign.MemorySegment =
        seg.asSlice(OFFSET_ERROR_BUF, RustBuffer.LAYOUT.byteSize())

    fun setErrorBuf(seg: java.lang.foreign.MemorySegment, errorBuf: java.lang.foreign.MemorySegment) {
        java.lang.foreign.MemorySegment.copy(errorBuf, 0, seg, OFFSET_ERROR_BUF, RustBuffer.LAYOUT.byteSize())
    }

    fun isSuccess(seg: java.lang.foreign.MemorySegment): Boolean = getCode(seg) == UNIFFI_CALL_SUCCESS
    fun isError(seg: java.lang.foreign.MemorySegment): Boolean = getCode(seg) == UNIFFI_CALL_ERROR
    fun isPanic(seg: java.lang.foreign.MemorySegment): Boolean = getCode(seg) == UNIFFI_CALL_UNEXPECTED_ERROR

    /** Allocate a new RustCallStatus in the given arena. */
    fun allocate(allocator: java.lang.foreign.SegmentAllocator): java.lang.foreign.MemorySegment {
        val seg = allocator.allocate(LAYOUT)
        seg.fill(0.toByte())
        return seg
    }

    // Slab allocator for RustCallStatus segments used by async callback error handling.
    // See UniffiSlabAllocator for the design rationale.
    private val STATUS_ALLOCATOR = UniffiSlabAllocator(LAYOUT, 1024)

    /**
     * Create a RustCallStatus with the given code and error buffer.
     * Used by async callback interface error handling.
     */
    fun create(code: Byte, errorBuf: java.lang.foreign.MemorySegment): java.lang.foreign.MemorySegment {
        val seg = STATUS_ALLOCATOR.allocate(LAYOUT)
        seg.fill(0.toByte())
        setCode(seg, code)
        setErrorBuf(seg, errorBuf)
        return seg
    }
}

// UNIFFI:FILE InternalException.kt
package {{ config.package_name() }}

open class InternalException(message: String) : RuntimeException(message)

// UNIFFI:FILE UniffiRustCallStatusErrorHandler.kt
package {{ config.package_name() }}

interface UniffiRustCallStatusErrorHandler<E : Exception> {
    fun lift(errorBuf: java.lang.foreign.MemorySegment): E
}

// UNIFFI:FILE UniffiSlabAllocator.kt
package {{ config.package_name() }}

// Thread-local slab allocator for short-lived native memory segments.
//
// Several FFM code paths need to allocate small struct-sized segments
// (RustBuffer at 24 bytes, RustCallStatus at 32 bytes) that are consumed
// immediately and never referenced again.
//
// Alternatives considered:
//   - Arena.global(): zero overhead but leaks permanently. At high call rates
//     this adds up fast (e.g. 100k calls × 24-32 bytes = 2.4-3.2 MB never
//     reclaimed).
//   - Arena.ofAuto() per call: correct but creates a new Arena +
//     PhantomReference per call, adding ~50-100ns of GC pressure to every
//     call.
//   - Thread-local reusable segment: zero overhead but causes SIGABRT — the
//     FFM runtime retains internal references to allocator-provided segments,
//     so reusing the same segment across calls corrupts FFM's internal state.
//
// This slab approach: allocate a batch of slots from one Arena.ofAuto(), then
// hand out slices. Each call gets a unique slice (avoiding the FFM reuse
// crash). When the slab is exhausted, a new one is allocated and the old one
// becomes GC-eligible once all its slices are consumed (immediate — callers
// read struct fields before the next call). Amortized cost: one Arena + one
// native malloc per `slots` calls.
internal class UniffiSlabAllocator(layout: java.lang.foreign.MemoryLayout, slots: Long) :
    java.lang.foreign.SegmentAllocator {
    private val slabBytes: Long = layout.byteSize() * slots
    private val alignment: Long = layout.byteAlignment()
    private val slab: ThreadLocal<java.lang.foreign.MemorySegment> =
        ThreadLocal.withInitial { java.lang.foreign.Arena.ofAuto().allocate(slabBytes, alignment) }
    private val offset: ThreadLocal<LongArray> = ThreadLocal.withInitial { LongArray(1) }

    override fun allocate(byteSize: Long, byteAlignment: Long): java.lang.foreign.MemorySegment {
        val off = offset.get()
        var s = slab.get()
        if (off[0] + byteSize > s.byteSize()) {
            s = java.lang.foreign.Arena.ofAuto().allocate(slabBytes, alignment)
            slab.set(s)
            off[0] = 0
        }
        val result = s.asSlice(off[0], byteSize)
        off[0] += byteSize
        return result
    }
}

// UNIFFI:FILE UniffiNullRustCallStatusErrorHandler.kt
package {{ config.package_name() }}

// UniffiRustCallStatusErrorHandler implementation for times when we don't
// expect a CALL_ERROR.
internal class UniffiNullRustCallStatusErrorHandler : UniffiRustCallStatusErrorHandler<InternalException> {
    override fun lift(errorBuf: java.lang.foreign.MemorySegment): InternalException {
        RustBuffer.free(errorBuf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// UNIFFI:FILE UniffiHelpers.kt
package {{ config.package_name() }}

// Helpers for calling Rust.
//
// In practice we usually need to be synchronized to call this safely, so it
// doesn't synchronize itself.
object UniffiHelpers {
    // Return a freshly-allocated, zeroed RustBuffer suitable for stuffing
    // into an error_buf slot when the caller hasn't produced a meaningful
    // error payload. The empty buffer is safe to pass back to Rust-side
    // free (all three fields are zero, so Rust treats it as no-op).
    internal fun zeroedRustBuffer(): java.lang.foreign.MemorySegment {
        val seg = java.lang.foreign.Arena.ofAuto().allocate(RustBuffer.LAYOUT)
        seg.fill(0.toByte())
        return seg
    }

    // Thread-local reusable RustCallStatus to avoid allocation on the hot path.
    private val REUSABLE_STATUS: ThreadLocal<java.lang.foreign.MemorySegment> =
        ThreadLocal.withInitial { java.lang.foreign.Arena.global().allocate(UniffiRustCallStatus.LAYOUT) }

    // Slab allocator for struct return values from FFI downcalls.
    // See UniffiSlabAllocator for the design rationale.
    private val RETURN_ALLOCATOR = UniffiSlabAllocator(RustBuffer.LAYOUT, 1024)

    fun interface UniffiRustCallFunction<U> {
        fun apply(allocator: java.lang.foreign.SegmentAllocator, status: java.lang.foreign.MemorySegment): U
    }

    fun interface UniffiRustCallVoidFunction {
        fun apply(allocator: java.lang.foreign.SegmentAllocator, status: java.lang.foreign.MemorySegment)
    }

    // Call a rust function that returns a Result<>. Pass in the error handler
    // that corresponds to the Err.
    @Throws(Exception::class)
    fun <U, E : Exception> uniffiRustCallWithError(
        errorHandler: UniffiRustCallStatusErrorHandler<E>,
        callback: UniffiRustCallFunction<U>,
    ): U {
        val status = REUSABLE_STATUS.get()
        status.fill(0.toByte())
        val returnValue = callback.apply(RETURN_ALLOCATOR, status)
        uniffiCheckCallStatus(errorHandler, status)
        return returnValue
    }

    // Overload for void-returning functions. Renamed (vs the Java
    // `uniffiRustCallWithError` overload) because Kotlin's SAM-overload
    // resolution can't reliably pick between this and the generic variant
    // when callers pass a lambda whose return type Kotlin tries to coerce.
    @Throws(Exception::class)
    fun <E : Exception> uniffiRustCallVoidWithError(
        errorHandler: UniffiRustCallStatusErrorHandler<E>,
        callback: UniffiRustCallVoidFunction,
    ) {
        val status = REUSABLE_STATUS.get()
        status.fill(0.toByte())
        callback.apply(RETURN_ALLOCATOR, status)
        uniffiCheckCallStatus(errorHandler, status)
    }

    // Check UniffiRustCallStatus and throw an error if the call wasn't successful.
    @Throws(Exception::class)
    fun <E : Exception> uniffiCheckCallStatus(
        errorHandler: UniffiRustCallStatusErrorHandler<E>,
        status: java.lang.foreign.MemorySegment,
    ) {
        when {
            UniffiRustCallStatus.isSuccess(status) -> return
            UniffiRustCallStatus.isError(status) -> throw errorHandler.lift(UniffiRustCallStatus.getErrorBuf(status))
            UniffiRustCallStatus.isPanic(status) -> {
                val errorBuf = UniffiRustCallStatus.getErrorBuf(status)
                if (RustBuffer.getLen(errorBuf) > 0) {
                    // TODO: lift the Rust panic message via the String
                    // converter once it's ported. For now free the buffer
                    // and surface a generic panic message so the runtime
                    // stays self-contained.
                    RustBuffer.free(errorBuf)
                    throw InternalException("Rust panic (string lift not yet implemented)")
                } else {
                    throw InternalException("Rust panic")
                }
            }
            else -> throw InternalException("Unknown rust call status: ${UniffiRustCallStatus.getCode(status)}")
        }
    }

    // Primitive-specialized variants that avoid autoboxing overhead. For each
    // primitive type we expose a functional interface + call + callWithError.
    {%- for (prim, suffix) in [("Long", "Long"), ("Int", "Int"), ("Short", "Short"), ("Byte", "Byte"), ("Float", "Float"), ("Double", "Double"), ("Boolean", "Boolean")] %}

    fun interface UniffiRustCall{{ suffix }}Function {
        fun apply(allocator: java.lang.foreign.SegmentAllocator, status: java.lang.foreign.MemorySegment): {{ prim }}
    }

    @Throws(Exception::class)
    fun <E : Exception> uniffiRustCallWithError{{ suffix }}(
        errorHandler: UniffiRustCallStatusErrorHandler<E>,
        callback: UniffiRustCall{{ suffix }}Function,
    ): {{ prim }} {
        val status = REUSABLE_STATUS.get()
        status.fill(0.toByte())
        val returnValue = callback.apply(RETURN_ALLOCATOR, status)
        uniffiCheckCallStatus(errorHandler, status)
        return returnValue
    }

    fun uniffiRustCall{{ suffix }}(callback: UniffiRustCall{{ suffix }}Function): {{ prim }} =
        uniffiRustCallWithError{{ suffix }}(UniffiNullRustCallStatusErrorHandler(), callback)
    {%- endfor %}

    // Call a rust function that returns a plain value.
    fun <U> uniffiRustCall(callback: UniffiRustCallFunction<U>): U =
        uniffiRustCallWithError(UniffiNullRustCallStatusErrorHandler(), callback)

    // Call a rust function that returns nothing. Renamed vs
    // `uniffiRustCall` to avoid Kotlin SAM-overload ambiguity.
    fun uniffiRustCallVoid(callback: UniffiRustCallVoidFunction) {
        uniffiRustCallVoidWithError(UniffiNullRustCallStatusErrorHandler(), callback)
    }

    fun <T> uniffiTraitInterfaceCall(
        callStatus: java.lang.foreign.MemorySegment,
        makeCall: java.util.function.Supplier<T>,
        writeReturn: java.util.function.Consumer<T>,
    ) {
        try {
            writeReturn.accept(makeCall.get())
        } catch (e: Exception) {
            UniffiRustCallStatus.setCode(callStatus, UniffiRustCallStatus.UNIFFI_CALL_UNEXPECTED_ERROR)
            // TODO: lower the Kotlin stack trace into `error_buf` via the
            // String converter once it's ported. In the interim, zero the
            // error_buf so Rust-side cleanup doesn't read uninitialized
            // data or attempt to free a stale pointer from the previous
            // call.
            UniffiRustCallStatus.setErrorBuf(callStatus, zeroedRustBuffer())
        }
    }

    private fun uniffiStackTraceToString(e: Throwable): String =
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            sw.toString()
        } catch (_: Throwable) {
            e.toString()
        }

    fun <T, E : Throwable> uniffiTraitInterfaceCallWithError(
        callStatus: java.lang.foreign.MemorySegment,
        makeCall: java.util.concurrent.Callable<T>,
        writeReturn: java.util.function.Consumer<T>,
        lowerError: java.util.function.Function<E, java.lang.foreign.MemorySegment>,
        errorClazz: Class<E>,
    ) {
        try {
            writeReturn.accept(makeCall.call())
        } catch (e: Exception) {
            if (errorClazz.isAssignableFrom(e.javaClass)) {
                @Suppress("UNCHECKED_CAST")
                val castedE = e as E
                UniffiRustCallStatus.setCode(callStatus, UniffiRustCallStatus.UNIFFI_CALL_ERROR)
                UniffiRustCallStatus.setErrorBuf(callStatus, lowerError.apply(castedE))
            } else {
                UniffiRustCallStatus.setCode(callStatus, UniffiRustCallStatus.UNIFFI_CALL_UNEXPECTED_ERROR)
                // TODO: lower the Kotlin stack trace via the String converter
                // once it's ported. In the interim, zero the error_buf so
                // Rust-side cleanup doesn't observe a stale pointer.
                UniffiRustCallStatus.setErrorBuf(callStatus, zeroedRustBuffer())
            }
        }
    }
}
