
// UNIFFI:FILE FfiConverterDuration.kt
package {{ config.package_name() }}

object FfiConverterDuration : FfiConverterRustBuffer<java.time.Duration> {
    override fun read(buf: java.nio.ByteBuffer): java.time.Duration {
        // Type mismatch (should be u64) but we check for overflow/underflow below
        val seconds = buf.getLong()
        // Type mismatch (should be u32) but we check for overflow/underflow below
        val nanoseconds = buf.getInt().toLong()
        if (seconds < 0) {
            throw java.time.DateTimeException(
                "Duration exceeds minimum or maximum value supported by uniffi"
            )
        }
        if (nanoseconds < 0) {
            throw java.time.DateTimeException(
                "Duration nanoseconds exceed minimum or maximum supported by uniffi"
            )
        }
        return java.time.Duration.ofSeconds(seconds, nanoseconds)
    }

    // 8 bytes for seconds, 4 bytes for nanoseconds
    override fun allocationSize(value: java.time.Duration): Long = 12L

    override fun write(value: java.time.Duration, buf: java.nio.ByteBuffer) {
        if (value.seconds < 0) {
            // Rust does not support negative Durations
            throw IllegalArgumentException("Invalid duration, must be non-negative")
        }

        if (value.nano < 0) {
            // Java docs guarantee nano is non-negative; defense-in-depth.
            throw IllegalArgumentException(
                "Invalid duration, nano value must be non-negative"
            )
        }

        // Type mismatch (should be u64) but Rust durations are non-negative so the long is safe.
        buf.putLong(value.seconds)
        // Type mismatch (should be u32) but values are 0..999_999_999 so the int round-trip is safe.
        buf.putInt(value.nano)
    }
}
