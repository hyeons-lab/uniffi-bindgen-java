
// UNIFFI:FILE FfiConverterLong.kt
package {{ config.package_name() }}

object FfiConverterLong : FfiConverter<Long, Long> {
    override fun lift(value: Long): Long = value
    override fun read(buf: java.nio.ByteBuffer): Long = buf.long
    override fun lower(value: Long): Long = value
    override fun allocationSize(value: Long): Long = 8L
    override fun write(value: Long, buf: java.nio.ByteBuffer) {
        buf.putLong(value)
    }
}
