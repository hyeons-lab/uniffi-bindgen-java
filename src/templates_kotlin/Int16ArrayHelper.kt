
// UNIFFI:FILE FfiConverterInt16Array.kt
package {{ config.package_name() }}

object FfiConverterInt16Array : FfiConverterRustBuffer<ShortArray> {
    override fun read(buf: java.nio.ByteBuffer): ShortArray {
        val len = buf.getInt()
        val arr = ShortArray(len)
        buf.asShortBuffer().get(arr)
        buf.position(buf.position() + len * 2)
        return arr
    }

    override fun allocationSize(value: ShortArray): Long = 4L + value.size.toLong() * 2L

    override fun write(value: ShortArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        buf.asShortBuffer().put(value)
        buf.position(buf.position() + value.size * 2)
    }
}
