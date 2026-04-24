
// UNIFFI:FILE UniffiCleaner.kt
package {{ config.package_name() }}

// Cleaner for Object finalization. Uses a PhantomReference-backed
// implementation with opportunistic inline draining to provide
// backpressure when objects are created faster than the background
// thread can process them (hot loops, benchmarks, callers that don't
// use close()). The structure mirrors `src/templates/
// ObjectCleanerHelper.java` — same contract, same threading shape,
// just Kotlin syntax.
internal interface UniffiCleaner {
    interface Cleanable {
        fun clean()
    }

    fun register(value: Any, cleanUpTask: Runnable): Cleanable

    companion object {
        fun create(): UniffiCleaner = UniffiBackpressureCleaner()
    }
}

// UNIFFI:FILE UniffiBackpressureCleaner.kt
package {{ config.package_name() }}

// PhantomReference + ReferenceQueue with opportunistic inline draining.
// Behavior matches the Java `UniffiBackpressureCleaner`:
//  1. Each register() creates a PhantomReference in a doubly-linked list
//     (so the refs aren't GC'd before their referents).
//  2. A daemon thread blocks on queue.remove() and runs cleanup.
//  3. Each register() call also does a non-blocking drain of up to
//     DRAIN_BATCH_SIZE pending refs on the calling thread, so steady
//     allocation pressure can't outrun the single cleaner thread.
//  4. Manual clean() via close() and GC-triggered clean() race via a
//     VarHandle CAS, guaranteeing the action fires at most once.
//  5. clean() synchronizes on the list sentinel to unlink, so dead
//     entries don't accumulate.
internal class UniffiBackpressureCleaner : UniffiCleaner {
    private val queue = java.lang.ref.ReferenceQueue<Any>()
    private val head = CleanableRef()

    init {
        val t = Thread {
            try {
                while (true) {
                    val ref = queue.remove()
                    if (ref is CleanableRef) {
                        ref.clean()
                    }
                }
            } catch (_: InterruptedException) {
                // Thread interrupted; exit.
            }
        }
        t.isDaemon = true
        t.name = "uniffi-cleaner"
        t.start()
    }

    override fun register(value: Any, cleanUpTask: Runnable): UniffiCleaner.Cleanable {
        // Opportunistic drain. queue.poll() is ~nanoseconds when empty,
        // so the per-register overhead is negligible.
        for (i in 0 until DRAIN_BATCH_SIZE) {
            val ref = queue.poll() ?: break
            if (ref is CleanableRef) {
                ref.clean()
            }
        }
        return CleanableRef(head, value, queue, cleanUpTask)
    }

    private companion object {
        private const val DRAIN_BATCH_SIZE = 8
    }

    // A PhantomReference that also implements Cleanable. Stored in a
    // doubly-linked list off the sentinel `head` so it stays strongly
    // referenced until cleanup runs.
    private class CleanableRef : java.lang.ref.PhantomReference<Any>, UniffiCleaner.Cleanable {
        private val list: CleanableRef
        private var prev: CleanableRef?
        private var next: CleanableRef?

        @Volatile
        private var action: Runnable? = null

        // Sentinel constructor.
        constructor() : super(null, null) {
            this.list = this
            this.prev = this
            this.next = this
        }

        // Normal constructor — inserts itself into the list.
        constructor(
            list: CleanableRef,
            referent: Any,
            q: java.lang.ref.ReferenceQueue<Any>,
            action: Runnable,
        ) : super(referent, q) {
            this.action = action
            this.list = list
            synchronized(list) {
                this.prev = list
                this.next = list.next
                list.next?.prev = this
                list.next = this
            }
        }

        override fun clean() {
            // Atomic swap ensures the action runs at most once, even
            // if called concurrently from close() and the cleaner
            // thread.
            val a = ACTION.getAndSet(this, null) as Runnable?
            if (a != null) {
                synchronized(list) {
                    // The swap above already makes this region racy-safe,
                    // but defensively null-check anyway.
                    next?.prev = prev
                    prev?.next = next
                    prev = null
                    next = null
                }
                a.run()
            }
        }

        companion object {
            private val ACTION: java.lang.invoke.VarHandle = try {
                java.lang.invoke.MethodHandles
                    .privateLookupIn(CleanableRef::class.java, java.lang.invoke.MethodHandles.lookup())
                    .findVarHandle(CleanableRef::class.java, "action", Runnable::class.java)
            } catch (e: ReflectiveOperationException) {
                throw ExceptionInInitializerError(e)
            }
        }
    }
}

// UNIFFI:FILE UniffiMarkers.kt
package {{ config.package_name() }}

// Marker singleton used to disambiguate the FFI-handle-wrapping
// constructor from user-facing constructors. The Java backend's
// `UniffiWithHandle` class serves the same purpose; Kotlin's `object`
// keyword makes it a one-liner.
internal object UniffiWithHandle

// Marker singleton for constructing fake (test-only) object wrappers
// with no live Rust handle. Parallel to the Java `NoHandle` class.
object NoHandle
