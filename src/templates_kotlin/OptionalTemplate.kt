
{%- let inner_type_name = inner_type|type_name(ci, config) %}
// UNIFFI:FILE {{ ffi_converter_name }}.kt
package {{ config.package_name() }}

// Kotlin uses its type system to express nullability directly via `T?`,
// so the `lift` / `lower` boundary handles a null sentinel byte without
// any wrapper class on the Kotlin side.
object {{ ffi_converter_name }} : FfiConverterRustBuffer<{{ inner_type_name }}?> {
    override fun read(buf: java.nio.ByteBuffer): {{ inner_type_name }}? {
        if (buf.get() == 0.toByte()) {
            return null
        }
        return {{ inner_type|read_fn }}(buf)
    }

    override fun allocationSize(value: {{ inner_type_name }}?): Long {
        if (value == null) {
            return 1L
        }
        return 1L + {{ inner_type|allocation_size_fn }}(value)
    }

    override fun write(value: {{ inner_type_name }}?, buf: java.nio.ByteBuffer) {
        if (value == null) {
            buf.put(0.toByte())
        } else {
            buf.put(1.toByte())
            {{ inner_type|write_fn }}(value, buf)
        }
    }
}
