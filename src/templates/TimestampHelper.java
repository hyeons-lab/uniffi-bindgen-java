
// UNIFFI:FILE FfiConverterTimestamp.java
package {{ config.package_name() }};

public enum FfiConverterTimestamp implements FfiConverterRustBuffer<java.time.Instant> {
    INSTANCE;

    @Override
    public java.time.Instant read(java.nio.ByteBuffer buf) {
        long seconds = buf.getLong();
        // Type mismatch (should be u32) but we check the i32 → u32 range below
        long nanoseconds = (long) buf.getInt();
        if (nanoseconds < 0 || nanoseconds > 999_999_999L) {
            throw new java.time.DateTimeException("Instant nanoseconds out of range, must be 0..999_999_999");
        }
        if (seconds >= 0) {
            return java.time.Instant.EPOCH.plus(java.time.Duration.ofSeconds(seconds, nanoseconds));
        } else {
            // `-Long.MIN_VALUE` overflows back to `Long.MIN_VALUE`; reject explicitly.
            if (seconds == Long.MIN_VALUE) {
                throw new java.time.DateTimeException("Instant seconds value Long.MIN_VALUE cannot be negated without overflow");
            }
            return java.time.Instant.EPOCH.minus(java.time.Duration.ofSeconds(-seconds, nanoseconds));
        }
    }

    // 8 bytes for seconds, 4 bytes for nanoseconds
    @Override
    public long allocationSize(java.time.Instant value) {
        return 12L;
    }

    @Override
    public void write(java.time.Instant value, java.nio.ByteBuffer buf) {
        java.time.Duration epochOffset = java.time.Duration.between(java.time.Instant.EPOCH, value);

        var sign = 1;
        if (epochOffset.isNegative()) {
            sign = -1;
            epochOffset = epochOffset.negated();
        }

        if (epochOffset.getNano() < 0) {
            // Java docs provide guarantee that nano will always be positive, so this should be impossible
            // See: https://docs.oracle.com/javase/8/docs/api/java/time/Instant.html
            throw new java.lang.IllegalArgumentException("Invalid timestamp, nano value must be non-negative");
        }

        buf.putLong(sign * epochOffset.getSeconds());
        // Type mismatch (should be u32) but since values will always be between 0 and 999,999,999 it should be OK
        buf.putInt(epochOffset.getNano());
    }
}
