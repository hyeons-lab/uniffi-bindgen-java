
// UNIFFI:FILE FfiConverterString.kt
package {{ config.package_name() }}

object FfiConverterString : FfiConverter<String, java.lang.foreign.MemorySegment> {
    // Note: we don't inherit from FfiConverterRustBuffer because we use a
    // special encoding when lowering/lifting. We use `RustBuffer.len` to
    // store the length rather than writing it into the buffer body.
    override fun lift(value: java.lang.foreign.MemorySegment): String =
        try {
            val byteArr = ByteArray(RustBuffer.getLen(value).toInt())
            RustBuffer.asByteBuffer(value).get(byteArr)
            String(byteArr, java.nio.charset.StandardCharsets.UTF_8)
        } finally {
            RustBuffer.free(value)
        }

    override fun read(buf: java.nio.ByteBuffer): String {
        val len = buf.int
        val byteArr = ByteArray(len)
        buf.get(byteArr)
        return String(byteArr, java.nio.charset.StandardCharsets.UTF_8)
    }

    private fun toUtf8(value: String): java.nio.ByteBuffer {
        // Guard against invalid UTF-16 (lone surrogates) so the conversion
        // produces a clean, decodable UTF-8 stream.
        val encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
        encoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            encoder.encode(java.nio.CharBuffer.wrap(value))
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw RuntimeException(e)
        }
    }

    override fun lower(value: String): java.lang.foreign.MemorySegment {
        val byteBuf = toUtf8(value)
        val rbuf = RustBuffer.alloc(byteBuf.limit().toLong())
        RustBuffer.asWriteByteBuffer(rbuf).put(byteBuf)
        RustBuffer.setLen(rbuf, byteBuf.limit().toLong())
        return rbuf
    }

    // We don't know exactly how many bytes the UTF-8 encoding will need,
    // so pessimistically allocate 3 bytes per UTF-16 code unit — always
    // enough even for worst-case surrogate expansions.
    override fun allocationSize(value: String): Long {
        val sizeForLength = 4L
        val sizeForString = value.length.toLong() * 3L
        return sizeForLength + sizeForString
    }

    override fun write(value: String, buf: java.nio.ByteBuffer) {
        val byteBuf = toUtf8(value)
        buf.putInt(byteBuf.limit())
        buf.put(byteBuf)
    }
}
