
// UNIFFI:FILE FfiConverterInt64Array.kt
package {{ config.package_name() }}

object FfiConverterInt64Array : FfiConverterRustBuffer<LongArray> {
    override fun read(buf: java.nio.ByteBuffer): LongArray {
        val len = buf.getInt()
        val arr = LongArray(len)
        buf.asLongBuffer().get(arr)
        buf.position(buf.position() + len * 8)
        return arr
    }

    override fun allocationSize(value: LongArray): Long = 4L + value.size.toLong() * 8L

    override fun write(value: LongArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        buf.asLongBuffer().put(value)
        buf.position(buf.position() + value.size * 8)
    }
}
