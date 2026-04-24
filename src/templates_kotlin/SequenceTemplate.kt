
{%- let inner_type_name = inner_type|type_name(ci, config) %}
// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

object {{ ffi_converter_name }} : FfiConverterRustBuffer<List<{{ inner_type_name }}>> {
    override fun read(buf: java.nio.ByteBuffer): List<{{ inner_type_name }}> {
        val len = buf.getInt()
        val list = ArrayList<{{ inner_type_name }}>(len)
        repeat(len) { list.add({{ inner_type|read_fn }}(buf)) }
        return list
    }

    override fun allocationSize(value: List<{{ inner_type_name }}>): Long {
        return 4L + value.sumOf { {{ inner_type|allocation_size_fn }}(it) }
    }

    override fun write(value: List<{{ inner_type_name }}>, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        value.forEach { {{ inner_type|write_fn }}(it, buf) }
    }
}
