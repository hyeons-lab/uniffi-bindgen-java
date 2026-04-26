
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
{%- if ci.has_async_callback_interface_definition() %}

    // ────────────────────────────────────────────────────────────────
    // Async callback interface support — Kotlin-side `suspend fun`
    // implementations that Rust can call back into. Mirrors upstream
    // uniffi-rs's `uniffiTraitInterfaceCallAsync` shape: launch the
    // user's `suspend fun` on `GlobalScope` (Rust drives the parent
    // task lifetime; structured concurrency is broken at the FFI
    // boundary by design), then route success / failure to Rust via
    // the completion callback the upcall args carried in.
    // ────────────────────────────────────────────────────────────────

    // Foreign-future handle map: Job per in-flight Kotlin coroutine.
    // Entries are removed by Rust's dropped callback (which also
    // cancels the Job if it is still running). On success or failure
    // the coroutine completes naturally; the entry stays until Rust
    // signals it has dropped the foreign future, which is the
    // standard handoff pattern (Rust holds the Arc until then).
    val foreignFutureHandleMap = UniffiHandleMap<kotlinx.coroutines.Job>()

    // FFI dropped-callback: Rust signalling that it's no longer
    // interested in the future's result. Cancel the Job if still
    // running. Safe against races (handle already gone → no-op).
    // Must NOT throw across the FFI boundary.
    private object UniffiForeignFutureDroppedCallbackImpl :
        UniffiForeignFutureDroppedCallback.Fn {
        override fun callback(handle: Long) {
            val job = foreignFutureHandleMap.removeOrNull(handle) ?: return
            if (!job.isCompleted) {
                job.cancel()
            }
        }
    }

    private val UNIFFI_FOREIGN_FUTURE_DROPPED_CALLBACK_STUB: java.lang.foreign.MemorySegment =
        UniffiForeignFutureDroppedCallback.toUpcallStub(
            UniffiForeignFutureDroppedCallbackImpl,
            java.lang.foreign.Arena.global(),
        )

    // Build a `ForeignFutureDroppedCallbackStruct` payload that
    // tells Rust how to cancel us — handle into the foreign-future
    // map + the singleton dropped-callback stub. Writes into the
    // out-segment Rust handed us at upcall time.
    private fun writeDroppedCallback(
        uniffiOutDroppedCallback: java.lang.foreign.MemorySegment,
        handle: Long,
    ) {
        // Upcall parameter segments have zero size; reinterpret to
        // the actual struct size before writing.
        val out = uniffiOutDroppedCallback.reinterpret(
            UniffiForeignFutureDroppedCallbackStruct.LAYOUT.byteSize()
        )
        UniffiForeignFutureDroppedCallbackStruct.sethandle(out, handle)
        UniffiForeignFutureDroppedCallbackStruct.setfree(
            out,
            UNIFFI_FOREIGN_FUTURE_DROPPED_CALLBACK_STUB,
        )
    }

    // Launch a Kotlin `suspend fun` that Rust is awaiting. The
    // success and error consumers are wired to invoke Rust's
    // completion callback — exactly one of them must fire per call,
    // and each fires consume an Arc reference on the Rust side, so
    // double-call must NEVER happen. The structure here ensures
    // the catch-block returns before falling through to handleSuccess.
    // In extreme circumstances (e.g. invoking the completion stub
    // itself throws) we may leak the Arc — better than double-free.
    //
    // CoroutineStart.LAZY is load-bearing: a fast-completing
    // `makeCall` would otherwise race the dropped-callback wiring,
    // and the success callback could fire before Rust received the
    // handle to cancel against. We start the Job only after the
    // handle is in the map and Rust has the dropped-callback struct.
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    inline fun <T> uniffiTraitInterfaceCallAsync(
        crossinline makeCall: suspend () -> T,
        crossinline handleSuccess: (T) -> Unit,
        crossinline handleError: (java.lang.foreign.MemorySegment) -> Unit,
        uniffiOutDroppedCallback: java.lang.foreign.MemorySegment,
    ) {
        val job = kotlinx.coroutines.GlobalScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY,
        ) coroutineBlock@ {
            val result: T = try {
                makeCall()
            } catch (e: Exception) {
                handleError(
                    UniffiRustCallStatus.create(
                        UniffiRustCallStatus.UNIFFI_CALL_UNEXPECTED_ERROR,
                        FfiConverterString.lower(e.stackTraceToString()),
                    )
                )
                return@coroutineBlock
            }
            handleSuccess(result)
        }
        val handle = foreignFutureHandleMap.insert(job)
        writeDroppedCallback(uniffiOutDroppedCallback, handle)
        job.start()
    }

    // Same as `uniffiTraitInterfaceCallAsync` but with typed-error
    // downconversion: exceptions of type `E` get lowered via
    // `lowerError` and shipped as a `UNIFFI_CALL_ERROR`; everything
    // else still goes through the unexpected-error path. `reified E`
    // gives us the runtime `is` check without passing `Class<E>`.
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    inline fun <T, reified E : Throwable> uniffiTraitInterfaceCallAsyncWithError(
        crossinline makeCall: suspend () -> T,
        crossinline handleSuccess: (T) -> Unit,
        crossinline handleError: (java.lang.foreign.MemorySegment) -> Unit,
        crossinline lowerError: (E) -> java.lang.foreign.MemorySegment,
        uniffiOutDroppedCallback: java.lang.foreign.MemorySegment,
    ) {
        val job = kotlinx.coroutines.GlobalScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY,
        ) coroutineBlock@ {
            val result: T = try {
                makeCall()
            } catch (e: Exception) {
                if (e is E) {
                    handleError(
                        UniffiRustCallStatus.create(
                            UniffiRustCallStatus.UNIFFI_CALL_ERROR,
                            lowerError(e),
                        )
                    )
                } else {
                    handleError(
                        UniffiRustCallStatus.create(
                            UniffiRustCallStatus.UNIFFI_CALL_UNEXPECTED_ERROR,
                            FfiConverterString.lower(e.stackTraceToString()),
                        )
                    )
                }
                return@coroutineBlock
            }
            handleSuccess(result)
        }
        val handle = foreignFutureHandleMap.insert(job)
        writeDroppedCallback(uniffiOutDroppedCallback, handle)
        job.start()
    }

    // For testing — exposed as `public` so consumer integration
    // tests can assert no Job leaks after async stress. Mirrors the
    // Java backend's `uniffiForeignFutureHandleCount()`.
    fun uniffiForeignFutureHandleCount(): Int = foreignFutureHandleMap.size()
{%- endif %}
}
