
{%- let e = ci.get_enum_definition(name).unwrap() %}
{%- let uniffi_trait_methods = e.uniffi_trait_methods() %}
{%- let has_trait_impls = uniffi_trait_methods.display_fmt.is_some() || uniffi_trait_methods.debug_fmt.is_some() || uniffi_trait_methods.eq_eq.is_some() || uniffi_trait_methods.hash_hash.is_some() || uniffi_trait_methods.ord_cmp.is_some() %}
{%- if e.is_flat() %}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

{# Flat enum: only Display/Debug → toString are emittable here.
   Kotlin enum class compiles to `java.lang.Enum`, which has
   final equals/hashCode/compareTo — exporting Eq/Hash/Ord on a
   flat enum produces invalid Kotlin (same constraint as the
   Java backend; uniffi-rs doesn't reject it at the macro
   level). The `;` after the last variant is required by Kotlin
   syntax when the enum body has any further declarations. #}
enum class {{ type_name }} {
    {%- for variant in e.variants() %}
    {{ variant|variant_name }}{% if !loop.last %},{% endif %}
    {%- endfor %}{% if e.variants().is_empty() || has_trait_impls %};{% endif %}
{% if has_trait_impls %}    {% call kotlin::uniffi_trait_impls(uniffi_trait_methods) %}
{% endif %}}

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
{% else %}
{#- Non-flat (associated-data) enum: render as `sealed class` with
   per-variant nested types. Variants with fields → `data class`;
   variants without fields → `object` (singleton, gives value
   equality without needing Kotlin 1.9+ `data object`). FfiConverter
   reads the discriminant + each variant's fields, writes the
   discriminant followed by each variant's fields in declaration
   order. Mirrors the Java backend's `sealed interface` / record
   shape. A Kotlin `sealed interface` would also be implementable
   by `data class` variants, but `sealed class` is preferred here
   so the parent type can carry future shared state (cleaner for
   error-as-object in P3l-errors, where the parent extends
   `kotlin.Exception(message)` and the variants delegate). -#}
// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

sealed class {{ type_name }} {
    {%- for variant in e.variants() %}
    {%- if variant.has_fields() %}
    data class {{ variant.name()|class_name(ci) }}(
        {%- for field in variant.fields() %}
        val {% call kotlin::field_name(field, loop.index) %}: {{ field|type_name(ci, config) }}{% if !loop.last %},{% endif %}
        {%- endfor %}
    ) : {{ type_name }}()
    {%- else %}
    object {{ variant.name()|class_name(ci) }} : {{ type_name }}()
    {%- endif %}
    {%- endfor %}
}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverterRustBuffer<{{ type_name }}> {
    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} =
        when (buf.getInt()) {
            {%- for variant in e.variants() %}
            {{ loop.index }} -> {{ type_name }}.{{ variant.name()|class_name(ci) }}{% if variant.has_fields() %}(
                {%- for field in variant.fields() %}
                {{ field|read_fn }}(buf){% if !loop.last %},{% endif %}
                {%- endfor %}
            ){% endif %}
            {%- endfor %}
            else -> throw RuntimeException("invalid enum value, something is very wrong!")
        }

    override fun allocationSize(value: {{ type_name }}): Long =
        when (value) {
            {%- for variant in e.variants() %}
            is {{ type_name }}.{{ variant.name()|class_name(ci) }} -> (
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
            is {{ type_name }}.{{ variant.name()|class_name(ci) }} -> {
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
