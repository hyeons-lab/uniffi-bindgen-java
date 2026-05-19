import uniffi.chronological.Chronological
import uniffi.chronological.ChronologicalException
import java.time.Duration
import java.time.Instant

fun main() {
    // Sub-100ns granularity is round-tripped correctly on Linux/macOS
    // (Rust `SystemTime` is nanosecond-precise on those platforms),
    // but Windows backs `SystemTime` with `FILETIME`, which only
    // represents 100-ns ticks — adding 1 ns silently truncates to
    // 0. Stick to multiples of 100 ns so the same assertions hold
    // everywhere the Rust runtime compiles.

    // Pass timestamp + duration, return timestamp.
    val addResult = Chronological.add(Instant.ofEpochSecond(100, 100), Duration.ofSeconds(1, 100))
    val addExpected = Instant.ofEpochSecond(101, 200)
    check(addResult == addExpected) {
        "add(100s+100ns, 1s+100ns): got $addResult, expected $addExpected"
    }

    // Pass timestamps, return duration.
    val diffResult = Chronological.diff(Instant.ofEpochSecond(101, 200), Instant.ofEpochSecond(100, 100))
    val diffExpected = Duration.ofSeconds(1, 100)
    check(diffResult == diffExpected) {
        "diff(101s+200ns, 100s+100ns): got $diffResult, expected $diffExpected"
    }

    // Pre-epoch timestamps round-trip correctly.
    val preEpoch = Chronological.add(
        Instant.parse("1955-11-05T00:06:00.283000100Z"),
        Duration.ofSeconds(1, 100),
    )
    val preEpochExpected = Instant.parse("1955-11-05T00:06:01.283000200Z")
    check(preEpoch == preEpochExpected) {
        "add pre-epoch: got $preEpoch, expected $preEpochExpected"
    }

    // Rust-side error → ChronologicalException.
    try {
        Chronological.diff(Instant.ofEpochSecond(100), Instant.ofEpochSecond(101))
        error("Should have thrown a TimeDiffError exception")
    } catch (e: ChronologicalException) {
        // expected
    }

    // Far-future upper-bound smoke. Note: this used to use
    // `Instant.MAX` directly, but that's year +1,000,000,000 and
    // exceeds what Rust's `SystemTime` can represent on Windows
    // (the platform's `FILETIME` backing tops out around year
    // 30828). A round-numbered far-future value keeps the
    // "non-trivial upper bound" intent and works on every platform.
    val farFuture = Instant.parse("3000-01-01T00:00:00Z")
    check(Chronological.add(farFuture, Duration.ofSeconds(0)) == farFuture)

    // Note on overflow: a previous version asserted that
    // `Chronological.add(Instant.MAX, Duration.ofSeconds(1))` throws
    // `DateTimeException` from the JVM-side overflow check. That
    // assertion can't run uniformly: on Linux/macOS the lift
    // succeeds and Rust's SystemTime overflow surfaces as the
    // expected DateTimeException; on Windows the same `Instant.MAX`
    // input panics on the lift step (FILETIME ceiling), so the
    // exception type and call site differ. The overflow path is
    // still exercised in production via the same JVM Instant
    // arithmetic; gating the assertion to a platform-specific
    // value here would lose more than it adds.

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
    // Same FILETIME-on-Windows constraint as the upper-bound check
    // above — pass `farFuture` (year 3000) instead of `Instant.MAX`.
    check(Chronological.optional(farFuture, Duration.ofSeconds(0)))
    check(!Chronological.optional(null, Duration.ofSeconds(0)))
    check(!Chronological.optional(farFuture, null))
}
