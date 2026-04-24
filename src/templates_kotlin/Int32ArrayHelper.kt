
// UNIFFI:FILE FfiConverterInt32Array.kt
package {{ config.package_name() }}

object FfiConverterInt32Array : FfiConverterRustBuffer<IntArray> {
    override fun read(buf: java.nio.ByteBuffer): IntArray {
        val len = buf.getInt()
        val arr = IntArray(len)
        buf.asIntBuffer().get(arr)
        buf.position(buf.position() + len * 4)
        return arr
    }

    override fun allocationSize(value: IntArray): Long = 4L + value.size.toLong() * 4L

    override fun write(value: IntArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        buf.asIntBuffer().put(value)
        buf.position(buf.position() + value.size * 4)
    }
}
