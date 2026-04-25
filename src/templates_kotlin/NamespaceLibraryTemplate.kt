
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

// FFM-based library binding. Each FFI function gets a `MethodHandle` field
// + a wrapper function that performs the `invokeExact` call. The loop
// below iterates `ci.iter_ffi_function_definitions()` so every runtime AND
// per-component FFI function gets bound at class-init time.
internal object UniffiLib {
    private val LINKER: java.lang.foreign.Linker = java.lang.foreign.Linker.nativeLinker()
    private val SYMBOLS: java.lang.foreign.SymbolLookup

    {%- if ci.contains_object_types() %}
    // Library-wide cleaner for object finalization. Instantiated lazily
    // at class-init; every object wrapper registers a
    // `UniffiCleanAction` with this shared cleaner on construction.
    @JvmField internal val CLEANER: UniffiCleaner = UniffiCleaner.create()
    {%- endif %}

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

    {% for func in ci.iter_ffi_function_definitions() -%}
    // {{ func.name() }}
    {%- match func.return_type() %}
    {%- when Some(return_type) %}
    {%- if return_type|ffi_type_is_struct %}
    private val MH_{{ func.name() }}: java.lang.invoke.MethodHandle = findDowncallHandle(
        "{{ func.name() }}",
        java.lang.foreign.FunctionDescriptor.of(
            {{ return_type|ffi_value_layout }},
            {%- for arg in func.arguments() %}
            {{ arg.type_().borrow()|ffi_value_layout }},
            {%- endfor %}
            {%- if func.has_rust_call_status_arg() %}
            java.lang.foreign.ValueLayout.ADDRESS,
            {%- endif %}
        ),
    )

    fun {{ func.name() }}(
        allocator: java.lang.foreign.SegmentAllocator,
        {%- for arg in func.arguments() %}
        {{ arg.name()|var_name }}: {{ arg.type_().borrow()|ffi_type_name }},
        {%- endfor %}
        {%- if func.has_rust_call_status_arg() %}
        uniffiOutErr: java.lang.foreign.MemorySegment,
        {%- endif %}
    ): java.lang.foreign.MemorySegment =
        try {
            MH_{{ func.name() }}.invokeExact(
                allocator,
                {%- for arg in func.arguments() %}
                {{ arg.name()|var_name }},
                {%- endfor %}
                {%- if func.has_rust_call_status_arg() %}
                uniffiOutErr,
                {%- endif %}
            ){{ return_type|ffi_invoke_exact_cast }}
        } catch (ex: Throwable) {
            throw AssertionError("invokeExact failed", ex)
        }
    {%- else %}
    private val MH_{{ func.name() }}: java.lang.invoke.MethodHandle = findDowncallHandle(
        "{{ func.name() }}",
        java.lang.foreign.FunctionDescriptor.of(
            {{ return_type|ffi_value_layout }},
            {%- for arg in func.arguments() %}
            {{ arg.type_().borrow()|ffi_value_layout }},
            {%- endfor %}
            {%- if func.has_rust_call_status_arg() %}
            java.lang.foreign.ValueLayout.ADDRESS,
            {%- endif %}
        ),
    )

    fun {{ func.name() }}(
        {%- for arg in func.arguments() %}
        {{ arg.name()|var_name }}: {{ arg.type_().borrow()|ffi_type_name }},
        {%- endfor %}
        {%- if func.has_rust_call_status_arg() %}
        uniffiOutErr: java.lang.foreign.MemorySegment,
        {%- endif %}
    ): {{ return_type|ffi_type_name }} =
        try {
            MH_{{ func.name() }}.invokeExact(
                {%- for arg in func.arguments() %}
                {{ arg.name()|var_name }},
                {%- endfor %}
                {%- if func.has_rust_call_status_arg() %}
                uniffiOutErr,
                {%- endif %}
            ){{ return_type|ffi_invoke_exact_cast }}
        } catch (ex: Throwable) {
            throw AssertionError("invokeExact failed", ex)
        }
    {%- endif %}
    {%- when None %}
    private val MH_{{ func.name() }}: java.lang.invoke.MethodHandle = findDowncallHandle(
        "{{ func.name() }}",
        java.lang.foreign.FunctionDescriptor.ofVoid(
            {%- for arg in func.arguments() %}
            {{ arg.type_().borrow()|ffi_value_layout }},
            {%- endfor %}
            {%- if func.has_rust_call_status_arg() %}
            java.lang.foreign.ValueLayout.ADDRESS,
            {%- endif %}
        ),
    )

    fun {{ func.name() }}(
        {%- for arg in func.arguments() %}
        {{ arg.name()|var_name }}: {{ arg.type_().borrow()|ffi_type_name }},
        {%- endfor %}
        {%- if func.has_rust_call_status_arg() %}
        uniffiOutErr: java.lang.foreign.MemorySegment,
        {%- endif %}
    ) {
        try {
            MH_{{ func.name() }}.invokeExact(
                {%- for arg in func.arguments() %}
                {{ arg.name()|var_name }},
                {%- endfor %}
                {%- if func.has_rust_call_status_arg() %}
                uniffiOutErr,
                {%- endif %}
            )
        } catch (ex: Throwable) {
            throw AssertionError("invokeExact failed", ex)
        }
    }
    {%- endmatch %}

    {% endfor %}

    // Integrity checks must run after all MethodHandle fields are initialized.
    init {
        NamespaceLibrary.uniffiCheckContractApiVersion()
        // Checksum verification is skipped in this revision; see
        // NamespaceLibrary.uniffiCheckApiChecksums().
    }
}

{#- FFI callback-function helper classes and FFI struct helper
   classes. Uniffi scaffolding emits these unconditionally
   (async-support future-completion structs, clone/free callbacks,
   etc.), so every generated binding carries them regardless of
   user type usage. Each is emitted as its own `// UNIFFI:FILE`
   block — the splitter pulls them out into individual `.kt` files.
-#}
{%- for def in ci.ffi_definitions() %}
{%- match def %}
{%- when FfiDefinition::Function(_) %}
{#- Functions are handled in the MethodHandle loop above. -#}
{%- when FfiDefinition::CallbackFunction(callback) %}

// UNIFFI:FILE {{ callback.name()|ffi_callback_name }}.kt
package {{ config.package_name() }}

internal object {{ callback.name()|ffi_callback_name }} {
    val DESCRIPTOR: java.lang.foreign.FunctionDescriptor =
        java.lang.foreign.FunctionDescriptor.{%- match callback.return_type() -%}
        {%- when Some(return_type) -%}
        of({{ return_type|ffi_value_layout }}
        {%- for arg in callback.arguments() %}, {{ arg.type_().borrow()|ffi_value_layout }}{% endfor -%}
        {%- if callback.has_rust_call_status_arg() %}, java.lang.foreign.ValueLayout.ADDRESS{% endif -%}
        )
        {%- when None -%}
        ofVoid(
        {%- for arg in callback.arguments() %}{{ arg.type_().borrow()|ffi_value_layout }}{% if !loop.last %}, {% endif %}{% endfor -%}
        {%- if callback.has_rust_call_status_arg() %}{% if !callback.arguments().is_empty() %}, {% endif %}java.lang.foreign.ValueLayout.ADDRESS{% endif -%}
        )
        {%- endmatch %}

    fun interface Fn {
        fun callback(
            {%- for arg in callback.arguments() %}
            {{ arg.name().borrow()|var_name }}: {{ arg.type_().borrow()|ffi_type_name }}{% if !loop.last %},{% endif %}
            {%- endfor %}
            {%- if callback.has_rust_call_status_arg() %}{% if !callback.arguments().is_empty() %},{% endif %}
            uniffiCallStatus: java.lang.foreign.MemorySegment
            {%- endif %}
        ){%- match callback.return_type() %}{%- when Some(return_type) %}: {{ return_type|ffi_type_name }}{%- when None %}{%- endmatch %}
    }

    fun toUpcallStub(fn: Fn, arena: java.lang.foreign.Arena): java.lang.foreign.MemorySegment {
        try {
            val handle = java.lang.invoke.MethodHandles.lookup()
                .findVirtual(
                    Fn::class.java,
                    "callback",
                    java.lang.invoke.MethodType.methodType(
                        {%- match callback.return_type() %}
                        {%- when Some(return_type) %}
                        {{ return_type|ffi_type_name }}::class.javaPrimitiveType ?: {{ return_type|ffi_type_name }}::class.java,
                        {%- when None %}
                        Void.TYPE,
                        {%- endmatch %}
                        {%- for arg in callback.arguments() %}
                        {{ arg.type_().borrow()|ffi_type_name }}::class.javaPrimitiveType ?: {{ arg.type_().borrow()|ffi_type_name }}::class.java{% if !loop.last %},{% endif %}
                        {%- endfor %}
                        {%- if callback.has_rust_call_status_arg() %}
                        {% if !callback.arguments().is_empty() %},{% endif %}java.lang.foreign.MemorySegment::class.java
                        {%- endif %}
                    ),
                )
                .bindTo(fn)
            return java.lang.foreign.Linker.nativeLinker().upcallStub(handle, DESCRIPTOR, arena)
        } catch (e: ReflectiveOperationException) {
            throw AssertionError("Failed to create upcall stub", e)
        }
    }
}
{%- when FfiDefinition::Struct(ffi_struct) %}

// UNIFFI:FILE {{ ffi_struct.name()|ffi_struct_name }}.kt
package {{ config.package_name() }}

internal object {{ ffi_struct.name()|ffi_struct_name }} {
    val LAYOUT: java.lang.foreign.StructLayout = java.lang.foreign.MemoryLayout.structLayout(
        {{ ffi_struct|ffi_struct_layout_body }}
    )
    {%- for field in ffi_struct.fields() %}
    private val OFFSET_{{ field.name()|var_name_raw }}: Long = LAYOUT.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("{{ field.name()|var_name_raw }}"))
    {%- endfor %}
    {%- for field in ffi_struct.fields() %}
    {%- if field.type_().borrow()|ffi_type_is_embedded_struct %}

    fun get{{ field.name()|var_name_raw }}(seg: java.lang.foreign.MemorySegment): java.lang.foreign.MemorySegment =
        seg.asSlice(OFFSET_{{ field.name()|var_name_raw }}, {{ field.type_().borrow()|ffi_struct_type_name }}.LAYOUT.byteSize())

    fun set{{ field.name()|var_name_raw }}(seg: java.lang.foreign.MemorySegment, value: java.lang.foreign.MemorySegment) {
        java.lang.foreign.MemorySegment.copy(value, 0L, seg, OFFSET_{{ field.name()|var_name_raw }}, {{ field.type_().borrow()|ffi_struct_type_name }}.LAYOUT.byteSize())
    }
    {%- else %}

    fun get{{ field.name()|var_name_raw }}(seg: java.lang.foreign.MemorySegment): {{ field.type_().borrow()|ffi_type_name }} =
        seg.get({{ field.type_().borrow()|ffi_value_layout_unaligned }}, OFFSET_{{ field.name()|var_name_raw }}){{ field.type_().borrow()|ffi_invoke_exact_cast }}

    fun set{{ field.name()|var_name_raw }}(seg: java.lang.foreign.MemorySegment, value: {{ field.type_().borrow()|ffi_type_name }}) {
        seg.set({{ field.type_().borrow()|ffi_value_layout_unaligned }}, OFFSET_{{ field.name()|var_name_raw }}, value)
    }
    {%- endif %}
    {%- endfor %}

    fun allocate(allocator: java.lang.foreign.SegmentAllocator): java.lang.foreign.MemorySegment {
        val seg = allocator.allocate(LAYOUT)
        seg.fill(0.toByte())
        return seg
    }
}
{%- endmatch %}
{%- endfor %}

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
