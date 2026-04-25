
{%- let rec = ci.get_record_definition(name).unwrap() %}
{%- let uniffi_trait_methods = rec.uniffi_trait_methods() %}
{%- let has_trait_impls = uniffi_trait_methods.display_fmt.is_some() || uniffi_trait_methods.debug_fmt.is_some() || uniffi_trait_methods.eq_eq.is_some() || uniffi_trait_methods.hash_hash.is_some() || uniffi_trait_methods.ord_cmp.is_some() %}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

{% if rec.has_fields() -%}
data class {{ type_name }}(
    {%- for field in rec.fields() %}
    val {{ field.name()|var_name }}: {{ field|type_name(ci, config) }}{% if !loop.last %},{% endif %}
    {%- endfor %}
){% if uniffi_trait_methods.ord_cmp.is_some() %} : Comparable<{{ type_name }}>{% endif %}{% if has_trait_impls %} {
    {#- `#[uniffi::export(Eq, Ord, Hash, Display)]` proc-macro trait
       overrides. Each override routes through a Rust-side FFI call,
       so equality / ordering / hashing / Display reflect the Rust
       impl (which can deliberately ignore some fields). Explicit
       declarations win over `data class`'s auto-generated equals /
       hashCode / toString. -#}
    {% call kotlin::uniffi_trait_impls(uniffi_trait_methods) %}
}{% endif %}
{%- else -%}
object {{ type_name }}{% if uniffi_trait_methods.ord_cmp.is_some() %} : Comparable<{{ type_name }}>{% endif %}{% if has_trait_impls %} {
    {% call kotlin::uniffi_trait_impls(uniffi_trait_methods) %}
}{% endif %}
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
