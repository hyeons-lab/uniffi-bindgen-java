
{%- let ffi_type = builtin|ffi_type %}
{%- match config.custom_types.get(name.as_str()) %}
{%- when None %}
{#- No user-provided config: auto-generate a `data class <Name>(val
   value: <Builtin>)` newtype wrapper plus a delegating
   FfiConverter. -#}

// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

data class {{ type_name }}(
    val value: {{ builtin|type_name(ci, config) }},
)

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverter<{{ type_name }}, {{ ffi_type.borrow()|ffi_type_name }}> {
    override fun lift(value: {{ ffi_type.borrow()|ffi_type_name }}): {{ type_name }} =
        {{ type_name }}({{ builtin|lift_fn }}(value))

    override fun lower(value: {{ type_name }}): {{ ffi_type.borrow()|ffi_type_name }} =
        {{ builtin|lower_fn }}(value.value)

    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} =
        {{ type_name }}({{ builtin|read_fn }}(buf))

    override fun allocationSize(value: {{ type_name }}): Long =
        {{ builtin|allocation_size_fn }}(value.value)

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        {{ builtin|write_fn }}(value.value, buf)
    }
}
{%- when Some(custom_type_config) %}
{#- User-provided config: optionally override the wrapper type name
   and inject lift/lower expression templates. Imports the caller's
   target type if configured. -#}

{%- match custom_type_config.type_name %}
{%- when Some(concrete_type_name) %}

// UNIFFI:FILE {{ type_name }}.kt
package {{ config.package_name() }}

{%- match custom_type_config.imports %}
{%- when Some(imports) %}
{%- for import_name in imports %}
import {{ import_name }}
{%- endfor %}
{%- else %}
{%- endmatch %}

data class {{ type_name }}(
    val value: {{ concrete_type_name }},
)
{%- else %}
{%- endmatch %}

// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

{%- match custom_type_config.imports %}
{%- when Some(imports) %}
{%- for import_name in imports %}
import {{ import_name }}
{%- endfor %}
{%- else %}
{%- endmatch %}

// FfiConverter with user-supplied lift/lower expressions.
object {{ ffi_converter_name }} : FfiConverter<{{ type_name }}, {{ ffi_type.borrow()|ffi_type_name }}> {
    override fun lift(value: {{ ffi_type.borrow()|ffi_type_name }}): {{ type_name }} {
        val builtinValue = {{ builtin|lift_fn }}(value)
        return try {
            {{ type_name }}({{ custom_type_config.lift("builtinValue") }})
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun lower(value: {{ type_name }}): {{ ffi_type.borrow()|ffi_type_name }} =
        try {
            val builtinValue = {{ custom_type_config.lower("value.value") }}
            {{ builtin|lower_fn }}(builtinValue)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    override fun read(buf: java.nio.ByteBuffer): {{ type_name }} =
        try {
            val builtinValue = {{ builtin|read_fn }}(buf)
            {{ type_name }}({{ custom_type_config.lift("builtinValue") }})
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    override fun allocationSize(value: {{ type_name }}): Long =
        try {
            val builtinValue = {{ custom_type_config.lower("value.value") }}
            {{ builtin|allocation_size_fn }}(builtinValue)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    override fun write(value: {{ type_name }}, buf: java.nio.ByteBuffer) {
        try {
            val builtinValue = {{ custom_type_config.lower("value.value") }}
            {{ builtin|write_fn }}(builtinValue, buf)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
{%- endmatch %}


