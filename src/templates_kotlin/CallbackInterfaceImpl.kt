
// UNIFFI:FILE UniffiCallbackInterface{{ name }}.kt
package {{ config.package_name() }}

{%- let trait_impl = "UniffiCallbackInterface{}"|format(name) %}

// Vtable assembly + per-method upcall stubs for callback interfaces.
// Each stub pulls the foreign-side object out of the handle map,
// invokes the requested method, and writes the result back into the
// Rust-provided out-param buffer. Mirrors `src/templates/
// CallbackInterfaceImpl.java`, minus the async and error-in-callback
// paths — those land with P3k / P3l respectively.
internal object UniffiCallbackInterface{{ name }} {
    internal val vtable: java.lang.foreign.MemorySegment

    init {
        // Arena.global() is load-bearing: vtables and upcall stubs
        // must outlive every FFI call they participate in. Auto arenas
        // would be reclaimable once a reference escapes into a struct,
        // producing use-after-free with delightful debugging traces.
        vtable = java.lang.foreign.Arena.global().allocate({{ vtable|ffi_struct_type_name }}.LAYOUT)
        {{ vtable|ffi_struct_type_name }}.setuniffiFree(
            vtable,
            {{ "CallbackInterfaceFree"|ffi_callback_name }}.toUpcallStub(UniffiFree, java.lang.foreign.Arena.global()),
        )
        {{ vtable|ffi_struct_type_name }}.setuniffiClone(
            vtable,
            {{ "CallbackInterfaceClone"|ffi_callback_name }}.toUpcallStub(UniffiClone, java.lang.foreign.Arena.global()),
        )
        {%- for (ffi_callback, meth) in vtable_methods.iter() %}
        {{ vtable|ffi_struct_type_name }}.set{{ meth.name()|var_name_raw }}(
            vtable,
            {{ ffi_callback.name()|ffi_callback_name }}.toUpcallStub({{ meth.name()|class_name(ci) }}Callback, java.lang.foreign.Arena.global()),
        )
        {%- endfor %}
    }

    // Registers the vtable with Rust. Called from UniffiLib init via
    // the generator-collected `initialization_fns()` list.
    fun register() {
        UniffiLib.{{ ffi_init_callback.name() }}(vtable)
    }

    {%- for (ffi_callback, meth) in vtable_methods.iter() %}
    {%- if meth.is_async() %}
    {%- let result_struct_name = meth.foreign_future_ffi_result_struct().name()|ffi_struct_name %}

    // Async callback method `{{ meth.name() }}` upcall: Rust is
    // calling INTO our Kotlin `suspend fun` impl. We launch the
    // user's coroutine via `UniffiAsyncHelpers.uniffiTraitInterfaceCallAsync`
    // (or `uniffiTraitInterfaceCallAsyncWithError` for throwing
    // methods); either path routes success / failure back to Rust
    // through the completion callback Rust handed us in
    // `uniffiFutureCallback`.
    internal object {{ meth.name()|class_name(ci) }}Callback : {{ ffi_callback.name()|ffi_callback_name }}.Fn {
        override fun callback(
            {%- for arg in ffi_callback.arguments() %}
            {{ arg.name().borrow()|var_name }}: {{ arg.type_().borrow()|ffi_type_name }}{% if !loop.last || (loop.last && ffi_callback.has_rust_call_status_arg()) %},{% endif %}
            {%- endfor %}
            {%- if ffi_callback.has_rust_call_status_arg() %}
            uniffiCallStatus: java.lang.foreign.MemorySegment,
            {%- endif %}
        ){%- match ffi_callback.return_type() %}{%- when Some(return_type) %}: {{ return_type|ffi_type_name }}{%- when None %}{%- endmatch %} {
            val uniffiObj = {{ ffi_converter_name }}.handleMap.get(uniffiHandle)
            // The completion callback always has signature
            // `(callbackData: Long, result: <ResultStruct>) -> Unit`.
            val uniffiCompletionDescriptor: java.lang.foreign.FunctionDescriptor =
                java.lang.foreign.FunctionDescriptor.ofVoid(
                    java.lang.foreign.ValueLayout.JAVA_LONG,
                    {{ result_struct_name }}.LAYOUT,
                )
            val uniffiHandleSuccess: ({% match meth.return_type() %}{%- when Some(return_type) %}{{ return_type|type_name(ci, config) }}{%- when None %}Unit{%- endmatch %}) -> Unit = { uniffiValue ->
                val uniffiResult = java.lang.foreign.Arena.ofAuto().allocate({{ result_struct_name }}.LAYOUT)
                {%- match meth.return_type() %}
                {%- when Some(return_type) %}
                {{ result_struct_name }}.setreturnValue(uniffiResult, {{ return_type|lower_fn }}(uniffiValue))
                {%- when None %}
                {%- endmatch %}
                // callStatus left zeroed = SUCCESS.
                try {
                    val globalCallback = java.lang.foreign.MemorySegment.ofAddress(uniffiFutureCallback.address())
                    val mh = java.lang.foreign.Linker.nativeLinker().downcallHandle(globalCallback, uniffiCompletionDescriptor)
                    mh.invokeExact(uniffiCallbackData, uniffiResult)
                } catch (t: Throwable) {
                    throw AssertionError("invokeExact failed", t)
                }
            }
            val uniffiHandleError: (java.lang.foreign.MemorySegment) -> Unit = { callStatus ->
                val uniffiResult = java.lang.foreign.Arena.ofAuto().allocate({{ result_struct_name }}.LAYOUT)
                {{ result_struct_name }}.setcallStatus(uniffiResult, callStatus)
                try {
                    val globalCallback = java.lang.foreign.MemorySegment.ofAddress(uniffiFutureCallback.address())
                    val mh = java.lang.foreign.Linker.nativeLinker().downcallHandle(globalCallback, uniffiCompletionDescriptor)
                    mh.invokeExact(uniffiCallbackData, uniffiResult)
                } catch (t: Throwable) {
                    throw AssertionError("invokeExact failed", t)
                }
            }
            // Lift args here (on the upcall thread) — `MemorySegment`
            // params are bound to the upcall thread's session, so
            // accessing them from a coroutine dispatcher worker after
            // the suspend lambda's `launch` would throw
            // `WrongThreadException`. The lifted values are pure
            // Kotlin types, safe to capture across threads.
            {%- for arg in meth.arguments() %}
            val uniffiLifted{{ loop.index }} = {{ arg|lift_fn }}({{ arg.name()|var_name }})
            {%- endfor %}
            {%- match meth.throws_type() %}
            {%- when None %}
            UniffiAsyncHelpers.uniffiTraitInterfaceCallAsync(
                makeCall = { uniffiObj.{{ meth.name()|fn_name() }}(
                    {%- for arg in meth.arguments() %}
                    uniffiLifted{{ loop.index }}{% if !loop.last %},{% endif %}
                    {%- endfor %}
                ) },
                handleSuccess = uniffiHandleSuccess,
                handleError = uniffiHandleError,
                uniffiOutDroppedCallback = uniffiOutDroppedCallback,
            )
            {%- when Some(error_type) %}
            UniffiAsyncHelpers.uniffiTraitInterfaceCallAsyncWithError<{% match meth.return_type() %}{%- when Some(return_type) %}{{ return_type|type_name(ci, config) }}{%- when None %}Unit{%- endmatch %}, {{ error_type|type_name(ci, config) }}>(
                makeCall = { uniffiObj.{{ meth.name()|fn_name() }}(
                    {%- for arg in meth.arguments() %}
                    uniffiLifted{{ loop.index }}{% if !loop.last %},{% endif %}
                    {%- endfor %}
                ) },
                handleSuccess = uniffiHandleSuccess,
                handleError = uniffiHandleError,
                lowerError = { e -> {{ error_type|lower_fn }}(e) },
                uniffiOutDroppedCallback = uniffiOutDroppedCallback,
            )
            {%- endmatch %}
        }
    }
    {%- else %}

    internal object {{ meth.name()|class_name(ci) }}Callback : {{ ffi_callback.name()|ffi_callback_name }}.Fn {
        override fun callback(
            {%- for arg in ffi_callback.arguments() %}
            {{ arg.name().borrow()|var_name }}: {{ arg.type_().borrow()|ffi_type_name }}{% if !loop.last || (loop.last && ffi_callback.has_rust_call_status_arg()) %},{% endif %}
            {%- endfor %}
            {%- if ffi_callback.has_rust_call_status_arg() %}
            uniffiCallStatus: java.lang.foreign.MemorySegment,
            {%- endif %}
        ){%- match ffi_callback.return_type() %}{%- when Some(return_type) %}: {{ return_type|ffi_type_name }}{%- when None %}{%- endmatch %} {
            {%- if ffi_callback.has_rust_call_status_arg() %}
            val uniffiCallStatusReinterpreted = uniffiCallStatus.reinterpret(UniffiRustCallStatus.LAYOUT.byteSize())
            {%- endif %}
            val uniffiObj = {{ ffi_converter_name }}.handleMap.get(uniffiHandle)
            val makeCall = {%- if meth.throws_type().is_some() %} java.util.concurrent.Callable {%- else %} java.util.function.Supplier {%- endif %} {
                uniffiObj.{{ meth.name()|fn_name() }}(
                    {%- for arg in meth.arguments() %}
                    {{ arg|lift_fn }}({{ arg.name()|var_name }}){% if !loop.last %},{% endif %}
                    {%- endfor %}
                )
            }
            {%- match meth.return_type() %}
            {%- when Some(return_type) %}
            {%- let ffi_return_type = return_type|ffi_type %}
            val writeReturn = java.util.function.Consumer<{{ return_type|type_name(ci, config) }}> { uniffiValue ->
                {%- if ffi_return_type.borrow()|ffi_type_is_embedded_struct %}
                val outReturn = uniffiOutReturn.reinterpret({{ ffi_return_type.borrow()|ffi_struct_type_name }}.LAYOUT.byteSize())
                val lowered = {{ return_type|lower_fn }}(uniffiValue)
                java.lang.foreign.MemorySegment.copy(lowered, 0L, outReturn, 0L, {{ ffi_return_type.borrow()|ffi_struct_type_name }}.LAYOUT.byteSize())
                {%- else %}
                val outReturn = uniffiOutReturn.reinterpret({{ ffi_return_type.borrow()|ffi_value_layout }}.byteSize())
                outReturn.set({{ ffi_return_type.borrow()|ffi_value_layout_unaligned }}, 0L, {{ return_type|lower_fn }}(uniffiValue))
                {%- endif %}
            }
            {%- when None %}
            // `Unit` (not `Void?`) so the type parameter unifies with
            // `makeCall: Supplier<Unit>` — Kotlin lambdas with no
            // expression-statement final value infer `Unit`, and
            // `uniffiTraitInterfaceCall<T>(makeCall: Supplier<T>,
            // writeReturn: Consumer<T>)` requires both sides agree.
            val writeReturn = java.util.function.Consumer<Unit> { _ -> }
            {%- endmatch %}

            {%- match meth.throws_type() %}
            {%- when None %}
            UniffiHelpers.uniffiTraitInterfaceCall(uniffiCallStatusReinterpreted, makeCall, writeReturn)
            {%- when Some(error_type) %}
            UniffiHelpers.uniffiTraitInterfaceCallWithError(
                uniffiCallStatusReinterpreted,
                makeCall,
                writeReturn,
                java.util.function.Function<{{ error_type|type_name(ci, config) }}, java.lang.foreign.MemorySegment> { e -> {{ error_type|lower_fn }}(e) },
                {{ error_type|type_name(ci, config) }}::class.java,
            )
            {%- endmatch %}
        }
    }
    {%- endif %}
    {%- endfor %}

    // Free — Rust drops the handle; we remove from the map.
    internal object UniffiFree : {{ "CallbackInterfaceFree"|ffi_callback_name }}.Fn {
        override fun callback(handle: Long) {
            {{ ffi_converter_name }}.handleMap.remove(handle)
        }
    }

    // Clone — Rust wants another reference; we clone in the map.
    internal object UniffiClone : {{ "CallbackInterfaceClone"|ffi_callback_name }}.Fn {
        override fun callback(handle: Long): Long =
            {{ ffi_converter_name }}.handleMap.clone(handle)
    }
}
