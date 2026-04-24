
// UNIFFI:FILE FfiConverterInt.kt
package {{ config.package_name() }}

object FfiConverterInt : FfiConverter<Int, Int> {
    override fun lift(value: Int): Int = value
    override fun read(buf: java.nio.ByteBuffer): Int = buf.int
    override fun lower(value: Int): Int = value
    override fun allocationSize(value: Int): Long = 4L
    override fun write(value: Int, buf: java.nio.ByteBuffer) {
        buf.putInt(value)
    }
}
