
// UNIFFI:FILE FfiConverterTimestamp.kt
package {{ config.package_name() }}

object FfiConverterTimestamp : FfiConverterRustBuffer<java.time.Instant> {
    override fun read(buf: java.nio.ByteBuffer): java.time.Instant {
        val seconds = buf.getLong()
        // Type mismatch (should be u32) but we check the i32 → u32 range below
        val nanoseconds = buf.getInt().toLong()
        if (nanoseconds < 0 || nanoseconds > 999_999_999L) {
            throw java.time.DateTimeException(
                "Instant nanoseconds out of range, must be 0..999_999_999"
            )
        }
        return if (seconds >= 0) {
            java.time.Instant.EPOCH.plus(java.time.Duration.ofSeconds(seconds, nanoseconds))
        } else {
            // `-Long.MIN_VALUE` overflows back to `Long.MIN_VALUE`; reject explicitly.
            if (seconds == Long.MIN_VALUE) {
                throw java.time.DateTimeException(
                    "Instant seconds value Long.MIN_VALUE cannot be negated without overflow"
                )
            }
            java.time.Instant.EPOCH.minus(java.time.Duration.ofSeconds(-seconds, nanoseconds))
        }
    }

    // 8 bytes for seconds, 4 bytes for nanoseconds
    override fun allocationSize(value: java.time.Instant): Long = 12L

    override fun write(value: java.time.Instant, buf: java.nio.ByteBuffer) {
        var epochOffset = java.time.Duration.between(java.time.Instant.EPOCH, value)

        var sign = 1
        if (epochOffset.isNegative) {
            sign = -1
            epochOffset = epochOffset.negated()
        }

        if (epochOffset.nano < 0) {
            // Java docs guarantee nano is non-negative; defense-in-depth.
            throw IllegalArgumentException(
                "Invalid timestamp, nano value must be non-negative"
            )
        }

        buf.putLong(sign * epochOffset.seconds)
        // Type mismatch (should be u32) but values are 0..999_999_999 so the int round-trip is safe.
        buf.putInt(epochOffset.nano)
    }
}
