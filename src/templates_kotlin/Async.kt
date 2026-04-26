
// UNIFFI:FILE UniffiAsyncHelpers.kt
package {{ config.package_name() }}

// Async runtime — turns Rust's poll-based future protocol into Kotlin
// `suspend fun` via `suspendCancellableCoroutine`. Mirrors upstream
// uniffi-rs's Kotlin Async.kt; differs only in how the continuation
// callback gets materialized — upstream uses JNA, we use FFM upcall
// stubs (consistent with the rest of this Kotlin backend).
//
// Generated `suspend fun` enters `UniffiAsyncHelpers.uniffiRustCallAsync`
// which loops `pollFunc` → `suspendCancellableCoroutine` until the Rust
// future reports READY, then calls `completeFunc` and lifts the result.
// `freeFunc` is invoked in `finally` so the Rust future is dropped on
// normal completion, exception, or coroutine cancellation.
//
// Runtime dependency: `org.jetbrains.kotlinx:kotlinx-coroutines-core`.
// Consumers must have it on classpath. Standard for any modern
// Kotlin async codebase.
internal object UniffiAsyncHelpers {
    const val UNIFFI_RUST_FUTURE_POLL_READY: Byte = 0
    const val UNIFFI_RUST_FUTURE_POLL_WAKE: Byte = 1

    // Continuation handle map. Rust calls our continuation callback with
    // the handle it was given when polling started, and we look up the
    // corresponding `CancellableContinuation` to resume. Every
    // `insert(continuation)` pairs with either:
    //   - A successful `removeOrNull` from the FFI callback (Rust polled
    //     and signalled READY/WAKE), or
    //   - A `removeOrNull` from `invokeOnCancellation` (the coroutine
    //     was cancelled before Rust got around to calling back).
    // Whichever runs first wins; the loser sees `null` and bails out.
    val continuationHandleMap =
        UniffiHandleMap<kotlinx.coroutines.CancellableContinuation<Byte>>()

    // FFI continuation callback: resume the suspended coroutine with the
    // poll result byte. Materialized once at class-init as a singleton
    // FFM upcall stub and reused for every async call. Safe against
    // races on cancellation: a missing handle, an already-resumed
    // continuation, or a cancelled continuation are all silently
    // ignored — the upcall must NOT throw across the FFI boundary.
    private object UniffiRustFutureContinuationCallbackImpl :
        UniffiRustFutureContinuationCallback.Fn {
        override fun callback(`data`: Long, pollResult: Byte) {
            val continuation = continuationHandleMap.removeOrNull(`data`) ?: return
            // `tryResume` returns a non-null token only when the
            // continuation was actually resumed; null means the
            // coroutine was cancelled or already resumed. In the
            // success case we must call `completeResume` to finalize.
            val token = continuation.tryResume(pollResult)
            if (token != null) {
                continuation.completeResume(token)
            }
        }
    }

    // Arena.global() because the upcall stub must outlive every async
    // call (which can span arbitrary suspension points). Auto arenas
    // would reclaim the stub once the only Java reference dies, leaving
    // Rust holding a dangling function pointer.
    private val UNIFFI_CONTINUATION_CALLBACK_STUB: java.lang.foreign.MemorySegment =
        UniffiRustFutureContinuationCallback.toUpcallStub(
            UniffiRustFutureContinuationCallbackImpl,
            java.lang.foreign.Arena.global(),
        )

    // Single generic helper that handles both value-returning and void
    // async calls. For void, callers pass `F = Unit`, `completeFunc`
    // returns `Unit` (FFM `_complete_void` returns nothing → Kotlin
    // lambda implicitly produces Unit), and `liftFunc = { Unit }`.
    @Throws(Exception::class)
    suspend fun <T, F, E : Exception> uniffiRustCallAsync(
        rustFuture: Long,
        pollFunc: (Long, java.lang.foreign.MemorySegment, Long) -> Unit,
        completeFunc: (java.lang.foreign.SegmentAllocator, Long, java.lang.foreign.MemorySegment) -> F,
        freeFunc: (Long) -> Unit,
        liftFunc: (F) -> T,
        errorHandler: UniffiRustCallStatusErrorHandler<E>,
    ): T {
        try {
            var pollResult: Byte
            do {
                pollResult = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    val handle = continuationHandleMap.insert(continuation)
                    // If the coroutine is cancelled before Rust calls
                    // the continuation callback, drop our handle entry
                    // so a late callback finds nothing and silently
                    // exits (`removeOrNull` returns null).
                    continuation.invokeOnCancellation {
                        continuationHandleMap.removeOrNull(handle)
                    }
                    pollFunc(rustFuture, UNIFFI_CONTINUATION_CALLBACK_STUB, handle)
                }
            } while (pollResult != UNIFFI_RUST_FUTURE_POLL_READY)

            return liftFunc(
                UniffiHelpers.uniffiRustCallWithError(errorHandler) { allocator, status ->
                    completeFunc(allocator, rustFuture, status)
                }
            )
        } finally {
            freeFunc(rustFuture)
        }
    }
}
