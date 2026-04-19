
// UNIFFI:FILE NamespaceLibrary.kt
package {{ config.package_name() }}

internal object NamespaceLibrary {
    @Synchronized
    fun findLibraryName(componentName: String): String {
        val libOverride = System.getProperty("uniffi.component.$componentName.libraryOverride")
        if (libOverride != null) {
            return libOverride
        }
        return "{{ config.cdylib_name() }}"
    }

    fun loadLibrary(): java.lang.foreign.SymbolLookup {
        val name = findLibraryName("{{ ci.namespace() }}")
        if (name.startsWith("/") || // Unix absolute path
            name.startsWith("\\\\") || // Windows UNC path
            (name.length > 2 && name[1] == ':') // Windows drive path (e.g. C:\)
        ) {
            System.load(name)
        } else {
            System.loadLibrary(name)
        }
        return java.lang.foreign.SymbolLookup.loaderLookup()
    }

    fun uniffiCheckContractApiVersion() {
        val bindingsContractVersion = {{ ci.uniffi_contract_version() }}
        val scaffoldingContractVersion = UniffiLib.{{ ci.ffi_uniffi_contract_version().name() }}()
        if (bindingsContractVersion != scaffoldingContractVersion) {
            throw RuntimeException("UniFFI contract version mismatch: try cleaning and rebuilding your project")
        }
    }

    // API checksum verification is skipped in this revision of the Kotlin
    // backend — it requires a per-function checksum loop
    // (`ci.iter_checksums()`) plus matching `MethodHandle`s on `UniffiLib`,
    // neither of which exist yet. The runtime is otherwise complete enough
    // to build and load; mismatches surface at call time rather than init
    // time until this is wired up.
    fun uniffiCheckApiChecksums() {
        // TODO: emit `if (UniffiLib.<name>() != <checksum>.toShort()) throw ...`
        // once per-function FFI wrappers are generated.
    }
}

// UNIFFI:FILE UniffiLib.kt
package {{ config.package_name() }}

// FFM-based library binding. This revision of the Kotlin templates emits
// only the runtime FFI functions the generated code currently references:
// `rustbuffer_alloc`, `rustbuffer_free`, and `uniffi_contract_version`.
// Per-component FFI functions (the user-facing ones) and per-function
// checksum probes will arrive once the Kotlin backend gains the Askama
// filter machinery that drives the equivalent loops in the Java template.
internal object UniffiLib {
    private val LINKER: java.lang.foreign.Linker = java.lang.foreign.Linker.nativeLinker()
    private val SYMBOLS: java.lang.foreign.SymbolLookup

    init {
        SYMBOLS = NamespaceLibrary.loadLibrary()
    }

    private fun findDowncallHandle(
        name: String,
        descriptor: java.lang.foreign.FunctionDescriptor,
    ): java.lang.invoke.MethodHandle =
        SYMBOLS.find(name)
            .map { s -> LINKER.downcallHandle(s, descriptor) }
            .orElseThrow { RuntimeException("Missing FFI symbol: $name") }

    // {{ ci.ffi_rustbuffer_alloc().name() }}
    private val MH_{{ ci.ffi_rustbuffer_alloc().name() }}: java.lang.invoke.MethodHandle =
        findDowncallHandle(
            "{{ ci.ffi_rustbuffer_alloc().name() }}",
            java.lang.foreign.FunctionDescriptor.of(
                RustBuffer.LAYOUT,
                java.lang.foreign.ValueLayout.JAVA_LONG,
                java.lang.foreign.ValueLayout.ADDRESS,
            ),
        )

    fun {{ ci.ffi_rustbuffer_alloc().name() }}(
        allocator: java.lang.foreign.SegmentAllocator,
        size: Long,
        uniffiOutErr: java.lang.foreign.MemorySegment,
    ): java.lang.foreign.MemorySegment =
        try {
            MH_{{ ci.ffi_rustbuffer_alloc().name() }}
                .invokeExact(allocator, size, uniffiOutErr) as java.lang.foreign.MemorySegment
        } catch (ex: Throwable) {
            throw AssertionError("invokeExact failed", ex)
        }

    // {{ ci.ffi_rustbuffer_free().name() }}
    private val MH_{{ ci.ffi_rustbuffer_free().name() }}: java.lang.invoke.MethodHandle =
        findDowncallHandle(
            "{{ ci.ffi_rustbuffer_free().name() }}",
            java.lang.foreign.FunctionDescriptor.ofVoid(
                RustBuffer.LAYOUT,
                java.lang.foreign.ValueLayout.ADDRESS,
            ),
        )

    fun {{ ci.ffi_rustbuffer_free().name() }}(
        buffer: java.lang.foreign.MemorySegment,
        uniffiOutErr: java.lang.foreign.MemorySegment,
    ) {
        try {
            MH_{{ ci.ffi_rustbuffer_free().name() }}.invokeExact(buffer, uniffiOutErr)
        } catch (ex: Throwable) {
            throw AssertionError("invokeExact failed", ex)
        }
    }

    // {{ ci.ffi_uniffi_contract_version().name() }}
    private val MH_{{ ci.ffi_uniffi_contract_version().name() }}: java.lang.invoke.MethodHandle =
        findDowncallHandle(
            "{{ ci.ffi_uniffi_contract_version().name() }}",
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT),
        )

    fun {{ ci.ffi_uniffi_contract_version().name() }}(): Int =
        try {
            MH_{{ ci.ffi_uniffi_contract_version().name() }}.invokeExact() as Int
        } catch (ex: Throwable) {
            throw AssertionError("invokeExact failed", ex)
        }

    // Integrity checks must run after all MethodHandle fields are initialized.
    init {
        NamespaceLibrary.uniffiCheckContractApiVersion()
        // Checksum verification is skipped in this revision; see
        // NamespaceLibrary.uniffiCheckApiChecksums().
    }
}

// UNIFFI:FILE UniffiInitializer.kt
package {{ config.package_name() }}

/**
 * Ensures the native library is initialized.
 * Call this function to force initialization before using any types from this library.
 */
object UniffiInitializer {
    /** Force initialization of the native library. */
    fun ensureInitialized() {
        // Force UniffiLib class to load (runs integrity checks and initialization functions).
        @Suppress("UNUSED_VARIABLE")
        val ignored: Class<*> = UniffiLib::class.java
    }
}
