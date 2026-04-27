import uniffi.chronological.Chronological
import uniffi.chronological.ChronologicalException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

fun main() {
    // Pass timestamp + duration, return timestamp.
    check(
        Chronological.add(Instant.ofEpochSecond(100, 100), Duration.ofSeconds(1, 1)) ==
            Instant.ofEpochSecond(101, 101),
    )

    // Pass timestamps, return duration.
    check(
        Chronological.diff(Instant.ofEpochSecond(101, 101), Instant.ofEpochSecond(100, 100)) ==
            Duration.ofSeconds(1, 1),
    )

    // Pre-epoch timestamps round-trip correctly.
    check(
        Chronological.add(
            Instant.parse("1955-11-05T00:06:00.283000001Z"),
            Duration.ofSeconds(1, 1),
        ) == Instant.parse("1955-11-05T00:06:01.283000002Z"),
    )

    // Rust-side error → ChronologicalException.
    try {
        Chronological.diff(Instant.ofEpochSecond(100), Instant.ofEpochSecond(101))
        error("Should have thrown a TimeDiffError exception")
    } catch (e: ChronologicalException) {
        // expected
    }

    // Instant.MAX upper bound passes through.
    check(Chronological.add(Instant.MAX, Duration.ofSeconds(0)) == Instant.MAX)

    // Overflow surfaces as DateTimeException from the JVM.
    try {
        Chronological.add(Instant.MAX, Duration.ofSeconds(1))
        error("Should have thrown a DateTimeException")
    } catch (e: DateTimeException) {
        // expected
    }

    // Rust-side `now()` is bracketed by two java.time.Instant.now() calls
    // with 10ms gaps. JVM clock may be lower-resolution than Rust's, so
    // the sleeps make sure each call advances.
    val javaBefore = Instant.now()
    Thread.sleep(10)
    val rustNow = Chronological.now()
    Thread.sleep(10)
    val javaAfter = Instant.now()
    check(javaBefore.isBefore(rustNow))
    check(javaAfter.isAfter(rustNow))

    // Optional values (nullable Instant + Duration arguments).
    check(Chronological.optional(Instant.MAX, Duration.ofSeconds(0)))
    check(!Chronological.optional(null, Duration.ofSeconds(0)))
    check(!Chronological.optional(Instant.MAX, null))
}
