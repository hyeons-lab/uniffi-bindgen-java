
{%- let e = ci.get_enum_definition(name).unwrap() %}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

sealed class {{ type_name }}(message: String) : kotlin.Exception(message) {
    {%- for variant in e.variants() %}
    class {{ variant|error_variant_name }}(message: String) : {{ type_name }}(message)
    {%- endfor %}
}

// UNIFFI:FILE {{ type_name }}ErrorHandler.kt
package {{ config.package_name() }}

class {{ type_name }}ErrorHandler : UniffiRustCallStatusErrorHandler<{{ type_name }}> {
    override fun lift(errorBuf: java.lang.foreign.MemorySegment): {{ type_name }} =
        {{ ffi_converter_name }}.lift(errorBuf)
}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverterRustBuffer<{{ type_name }}> {
    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} {
        return when (buf.getInt()) {
            {%- for variant in e.variants() %}
            {{ loop.index }} -> {{ type_name }}.{{ variant|error_variant_name }}(FfiConverterString.read(buf))
            {%- endfor %}
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: {{ type_name }}): Long = 4L

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        when (value) {
            {%- for variant in e.variants() %}
            is {{ type_name }}.{{ variant|error_variant_name }} -> {
                buf.putInt({{ loop.index }})
            }
            {%- endfor %}
        }
    }
}
