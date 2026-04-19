
// UNIFFI:FILE FfiConverterFloat.kt
package {{ config.package_name() }}

object FfiConverterFloat : FfiConverter<Float, Float> {
    override fun lift(value: Float): Float = value
    override fun read(buf: java.nio.ByteBuffer): Float = buf.float
    override fun lower(value: Float): Float = value
    override fun allocationSize(value: Float): Long = 4L
    override fun write(value: Float, buf: java.nio.ByteBuffer) {
        buf.putFloat(value)
    }
}
