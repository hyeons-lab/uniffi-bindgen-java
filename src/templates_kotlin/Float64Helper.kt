
// UNIFFI:FILE FfiConverterDouble.kt
package {{ config.package_name() }}

object FfiConverterDouble : FfiConverter<Double, Double> {
    override fun lift(value: Double): Double = value
    override fun read(buf: java.nio.ByteBuffer): Double = buf.double
    override fun lower(value: Double): Double = value
    override fun allocationSize(value: Double): Long = 8L
    override fun write(value: Double, buf: java.nio.ByteBuffer) {
        buf.putDouble(value)
    }
}
