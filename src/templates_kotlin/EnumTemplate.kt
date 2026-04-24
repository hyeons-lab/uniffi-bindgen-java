
{%- let e = ci.get_enum_definition(name).unwrap() %}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

enum class {{ type_name }} {
    {%- for variant in e.variants() %}
    {{ variant|variant_name }}{% if !loop.last %},{% endif %}
    {%- endfor -%}
    {% if e.variants().is_empty() %};{% endif %}
}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverterRustBuffer<{{ type_name }}> {
    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} {
        try {
            return {{ type_name }}.values()[buf.getInt() - 1]
        } catch (e: IndexOutOfBoundsException) {
            throw RuntimeException("invalid enum value, something is very wrong!!", e)
        }
    }

    override fun allocationSize(value: {{ type_name }}): Long = 4L

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}
