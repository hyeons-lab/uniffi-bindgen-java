
// UNIFFI:FILE UniffiHandleMap.kt
package {{ config.package_name() }}

// Maps odd 64-bit handles to foreign-side objects that Rust references
// via callback interfaces. Handles are always odd (LSB = 1) to
// distinguish them from Rust handles (LSB = 0) — the LSB check in
// `FfiConverter.lift()` routes between the two sides without an
// additional tag. Thread-safe by construction (ConcurrentHashMap +
// AtomicLong counter). Mirrors `src/templates/HandleMap.java`.
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
