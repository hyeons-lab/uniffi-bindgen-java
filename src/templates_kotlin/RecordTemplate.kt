
{%- let rec = ci.get_record_definition(name).unwrap() %}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

{% if rec.has_fields() -%}
data class {{ type_name }}(
    {%- for field in rec.fields() %}
    val {{ field.name()|var_name }}: {{ field|type_name(ci, config) }}{% if !loop.last %},{% endif %}
    {%- endfor %}
)
{%- else -%}
object {{ type_name }}
{%- endif %}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverterRustBuffer<{{ type_name }}> {
    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} {
        {%- if rec.has_fields() %}
        return {{ type_name }}(
            {%- for field in rec.fields() %}
            {{ field|read_fn }}(buf){% if !loop.last %},{% endif %}
            {%- endfor %}
        )
        {%- else %}
        return {{ type_name }}
        {%- endif %}
    }

    override fun allocationSize(value: {{ type_name }}): Long {
        {%- if rec.has_fields() %}
        return (
            {%- for field in rec.fields() %}
            {{ field|allocation_size_fn }}(value.{{ field.name()|var_name }}){% if !loop.last %} +{% endif %}
            {%- endfor %}
        )
        {%- else %}
        return 0L
        {%- endif %}
    }

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        {%- for field in rec.fields() %}
        {{ field|write_fn }}(value.{{ field.name()|var_name }}, buf)
        {%- endfor %}
    }
}
