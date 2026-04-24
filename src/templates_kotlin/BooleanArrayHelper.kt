
// UNIFFI:FILE FfiConverterBooleanArray.kt
package {{ config.package_name() }}

object FfiConverterBooleanArray : FfiConverterRustBuffer<BooleanArray> {
    override fun read(buf: java.nio.ByteBuffer): BooleanArray {
        val len = buf.getInt()
        val arr = BooleanArray(len)
        for (i in 0 until len) {
            arr[i] = buf.get() != 0.toByte()
        }
        return arr
    }

    override fun allocationSize(value: BooleanArray): Long = 4L + value.size.toLong()

    override fun write(value: BooleanArray, buf: java.nio.ByteBuffer) {
        buf.putInt(value.size)
        for (b in value) {
            buf.put(if (b) 1.toByte() else 0.toByte())
        }
    }
}
