
// UNIFFI:FILE FfiConverterByte.kt
package {{ config.package_name() }}

object FfiConverterByte : FfiConverter<Byte, Byte> {
    override fun lift(value: Byte): Byte = value
    override fun read(buf: java.nio.ByteBuffer): Byte = buf.get()
    override fun lower(value: Byte): Byte = value
    override fun allocationSize(value: Byte): Long = 1L
    override fun write(value: Byte, buf: java.nio.ByteBuffer) {
        buf.put(value)
    }
}
