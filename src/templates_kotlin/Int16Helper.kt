
// UNIFFI:FILE FfiConverterShort.kt
package {{ config.package_name() }}

object FfiConverterShort : FfiConverter<Short, Short> {
    override fun lift(value: Short): Short = value
    override fun read(buf: java.nio.ByteBuffer): Short = buf.short
    override fun lower(value: Short): Short = value
    override fun allocationSize(value: Short): Long = 2L
    override fun write(value: Short, buf: java.nio.ByteBuffer) {
        buf.putShort(value)
    }
}
