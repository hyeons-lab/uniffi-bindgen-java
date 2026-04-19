
// UNIFFI:FILE FfiConverterByteArray.kt
package {{ config.package_name() }}

object FfiConverterByteArray : FfiConverterRustBuffer<ByteArray> {
    override fun read(buf: java.nio.ByteBuffer): ByteArray {
        val len = buf.int
        val arr = ByteArray(len)
        buf.get(arr)
        return arr
    }

    override fun allocationSize(value: ByteArray): Long = (4 + value.size).toLong()

    override fun write(value: ByteArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        buf.put(value)
    }
}
