
// UNIFFI:FILE UniffiHandleMap.kt
package {{ config.package_name() }}

// Maps 64-bit handles to foreign-side objects that Rust references
// via callback interfaces. Handles allocated here are always odd
// (LSB = 1), reserving odd values for callback-interface handles and
// keeping them distinct from Rust-owned handles (LSB = 0). The
// matching LSB-based lift/lower dispatch in `FfiConverter`s for
// `[Trait, WithForeign]` objects is future work (P3j-c) — pure
// callback-interface FfiConverters in this revision unconditionally
// route through the handle map. Thread-safe by construction
// (ConcurrentHashMap + AtomicLong counter). Mirrors
// `src/templates/HandleMap.java`.
internal class UniffiHandleMap<T : Any> {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, T>()

    // Start at 1, increment by 2 → all handles are odd.
    private val counter = java.util.concurrent.atomic.AtomicLong(1L)

    fun size(): Int = map.size

    // Insert and return a fresh odd handle.
    fun insert(obj: T): Long {
        val handle = counter.getAndAdd(2L)
        map[handle] = obj
        return handle
    }

    fun get(handle: Long): T =
        map[handle] ?: throw InternalException("UniffiHandleMap.get: Invalid handle")

    // Single-use — removes from the map and returns the object.
    fun remove(handle: Long): T =
        map.remove(handle) ?: throw InternalException("UniffiHandleMap: Invalid handle")

    // Clone a handle: insert the same object again under a new handle.
    fun clone(handle: Long): Long = insert(get(handle))
}
