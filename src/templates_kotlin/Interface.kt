
// UNIFFI:FILE {{ interface_name }}.kt
package {{ config.package_name() }}

interface {{ interface_name }} {
    {%- for meth in methods.iter() %}
    {%- match meth.throws_type() %}
    {%- when Some(error_type) %}
    @Throws({{ error_type|type_name(ci, config) }}::class)
    {%- when None %}
    {%- endmatch %}
    fun {{ meth.name()|fn_name }}({% call kotlin::arg_list(meth) %}){%- match meth.return_type() -%}{%- when Some(return_type) -%}: {{ return_type|type_name(ci, config) }}{%- when None -%}{%- endmatch %}
    {%- endfor %}
}
