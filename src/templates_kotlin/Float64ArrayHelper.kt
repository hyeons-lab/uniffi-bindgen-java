
// UNIFFI:FILE FfiConverterFloat64Array.kt
package {{ config.package_name() }}

object FfiConverterFloat64Array : FfiConverterRustBuffer<DoubleArray> {
    override fun read(buf: java.nio.ByteBuffer): DoubleArray {
        val len = buf.getInt()
        val arr = DoubleArray(len)
        buf.asDoubleBuffer().get(arr)
        buf.position(buf.position() + len * 8)
        return arr
    }

    override fun allocationSize(value: DoubleArray): Long = 4L + value.size.toLong() * 8L

    override fun write(value: DoubleArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        buf.asDoubleBuffer().put(value)
        buf.position(buf.position() + value.size * 8)
    }
}
