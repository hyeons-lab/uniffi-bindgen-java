
{%- let key_type_name = key_type|type_name(ci, config) %}
{%- let value_type_name = value_type|type_name(ci, config) %}
// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverterRustBuffer<Map<{{ key_type_name }}, {{ value_type_name }}>> {
    override fun read(buf: java.nio.ByteBuffer): Map<{{ key_type_name }}, {{ value_type_name }}> {
        val len = buf.getInt()
        val map = HashMap<{{ key_type_name }}, {{ value_type_name }}>(len)
        repeat(len) {
            val k = {{ key_type|read_fn }}(buf)
            val v = {{ value_type|read_fn }}(buf)
            map[k] = v
        }
        return map
    }

    override fun allocationSize(value: Map<{{ key_type_name }}, {{ value_type_name }}>): Long {
        return 4L + value.entries.sumOf {
            {{ key_type|allocation_size_fn }}(it.key) + {{ value_type|allocation_size_fn }}(it.value)
        }
    }

    override fun write(value: Map<{{ key_type_name }}, {{ value_type_name }}>, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        for ((k, v) in value) {
            {{ key_type|write_fn }}(k, buf)
            {{ value_type|write_fn }}(v, buf)
        }
    }
}
