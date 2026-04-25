
{%- let cbi = ci.get_callback_interface_definition(name).unwrap() %}
{%- let ffi_init_callback = cbi.ffi_init_callback() %}
{%- let interface_name = cbi|type_name(ci, config) %}
{%- let methods = cbi.methods() %}
{%- let vtable = cbi.vtable() %}
{%- let vtable_methods = cbi.vtable_methods() %}

{% include "Interface.kt" %}
{% include "CallbackInterfaceImpl.kt" %}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

// FfiConverter that lowers {{ interface_name }} instances into
// odd-LSB handles (foreign-side) via the shared UniffiHandleMap.
internal object {{ ffi_converter_name }} : FfiConverterCallbackInterface<{{ interface_name }}>()
