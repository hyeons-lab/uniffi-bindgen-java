
{%- let e = ci.get_enum_definition(name).unwrap() %}
{% if e.is_flat() %}
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
{% else %}
{#- Non-flat (associated-data) error: each variant carries its own
   fields. The parent class extends `kotlin.Exception(message)` and
   each variant constructs a synthetic `field=value` message string
   so a stray `printStackTrace` shows useful state. FfiConverter
   reads/writes the discriminant + per-variant fields symmetrically
   (errors flow Rust → Kotlin via lift; lower exists for
   completeness if a foreign trait method ever rethrows). Mirrors
   the Java backend's `class <Name> extends Exception` shape with
   nested static classes per variant. -#}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

sealed class {{ type_name }}(message: String) : kotlin.Exception(message) {
    {%- for variant in e.variants() %}
    class {{ variant|error_variant_name }}(
        {%- for field in variant.fields() %}
        val {% call kotlin::field_name(field, loop.index) %}: {{ field|type_name(ci, config) }}{% if !loop.last %},{% endif %}
        {%- endfor %}
    ) : {{ type_name }}(
        {#- Build a `field=value, …` synthetic message so stack traces
           surface the variant's payload. Mirrors the Java backend's
           StringBuilder dance, just inlined as a Kotlin string
           interpolation. The label uses `field_name_unquoted` so a
           Rust field named `object` shows as `object=…` in the user-
           visible message rather than leaking backticks; the
           interpolation uses `${...}` form so backtick-escaped
           identifiers (e.g. `` `object` ``) parse correctly inside
           the Kotlin string template. -#}
        {%- if variant.fields().is_empty() -%}
        ""
        {%- else -%}
        "{% for field in variant.fields() %}{% call kotlin::field_name_unquoted(field, loop.index) %}=${{ "{" }}{% call kotlin::field_name(field, loop.index) %}}{% if !loop.last %}, {% endif %}{% endfor %}"
        {%- endif -%}
    )
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
    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} =
        when (buf.getInt()) {
            {%- for variant in e.variants() %}
            {{ loop.index }} -> {{ type_name }}.{{ variant|error_variant_name }}(
                {%- for field in variant.fields() %}
                {{ field|read_fn }}(buf){% if !loop.last %},{% endif %}
                {%- endfor %}
            )
            {%- endfor %}
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }

    override fun allocationSize(value: {{ type_name }}): Long =
        when (value) {
            {%- for variant in e.variants() %}
            is {{ type_name }}.{{ variant|error_variant_name }} -> (
                4L
                {%- for field in variant.fields() %}
                + {{ field|allocation_size_fn }}(value.{% call kotlin::field_name(field, loop.index) %})
                {%- endfor %}
            )
            {%- endfor %}
        }

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        when (value) {
            {%- for variant in e.variants() %}
            is {{ type_name }}.{{ variant|error_variant_name }} -> {
                buf.putInt({{ loop.index }})
                {%- for field in variant.fields() %}
                {{ field|write_fn }}(value.{% call kotlin::field_name(field, loop.index) %}, buf)
                {%- endfor %}
            }
            {%- endfor %}
        }
    }
}
{% endif %}
