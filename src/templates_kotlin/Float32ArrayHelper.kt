
// UNIFFI:FILE FfiConverterFloat32Array.kt
package {{ config.package_name() }}

object FfiConverterFloat32Array : FfiConverterRustBuffer<FloatArray> {
    override fun read(buf: java.nio.ByteBuffer): FloatArray {
        val len = buf.getInt()
        val arr = FloatArray(len)
        buf.asFloatBuffer().get(arr)
        buf.position(buf.position() + len * 4)
        return arr
    }

    override fun allocationSize(value: FloatArray): Long = 4L + value.size.toLong() * 4L

    override fun write(value: FloatArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        buf.asFloatBuffer().put(value)
        buf.position(buf.position() + value.size * 4)
    }
}
