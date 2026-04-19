
// UNIFFI:FILE FfiConverterBoolean.kt
package {{ config.package_name() }}

object FfiConverterBoolean : FfiConverter<Boolean, Byte> {
    override fun lift(value: Byte): Boolean = value.toInt() != 0
    override fun read(buf: java.nio.ByteBuffer): Boolean = lift(buf.get())
    override fun lower(value: Boolean): Byte = if (value) 1.toByte() else 0.toByte()
    override fun allocationSize(value: Boolean): Long = 1L
    override fun write(value: Boolean, buf: java.nio.ByteBuffer) {
        buf.put(lower(value))
    }
}
