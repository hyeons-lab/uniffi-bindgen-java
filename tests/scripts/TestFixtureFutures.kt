// Async round-trip — covers every shape exposed by uniffi-fixture-futures:
//
//   • Namespace async fns (`alwaysReady`, `void`, `sleep`, `sayAfter`)
//     including sequential vs concurrent (`async { ... }.await()`).
//   • Object methods that suspend (`megaphone.sayAfter`,
//     `megaphone.fallibleMe`, `megaphone.sayAfterWithTokio`).
//   • Async constructors emitted as companion-object factories
//     (`Megaphone.secondary()`).
//   • Async return of optional Object (`asyncMaybeNewMegaphone`).
//   • Trait async methods, both Rust- and Kotlin-implemented:
//     - `getSayAfterTraits()` / `getSayAfterUdlTraits()` round-trip.
//     - Foreign `KotlinAsyncParser` passed into Rust via
//       `asStringUsingTrait`, `tryFromStringUsingTrait`,
//       `delayUsingTrait`, `tryDelayUsingTrait`,
//       `cancelDelayUsingTrait` — including coroutine cancellation
//       semantics, typed errors raised from Kotlin into Rust, and
//       handle leak check via the public top-level
//       `uniffiForeignFutureHandleCount()` accessor.
//   • Tokio-runtime path (`sayAfterWithTokio`).
//   • Fallible async fn / method (`fallibleMe`, `fallibleStruct`).
//   • Async record return (`newMyRecord`).
//   • Broken sleep (multi-wake of the same future).
//   • Lock + timeout under cancellation (`useSharedResource`).
//
// Mirrors upstream uniffi-rs's `test_futures.kts` but adapted for
// this backend's signed-int parameter types, looser CI timing
// tolerances, and `check`-style assertions consistent with
// `TestFixtureCoverall.kt`.

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import uniffi.fixture.futures.AsyncParser
import uniffi.fixture.futures.Futures
import uniffi.fixture.futures.Megaphone
import uniffi.fixture.futures.MyException
import uniffi.fixture.futures.ParserException
import uniffi.fixture.futures.SharedResourceOptions
import uniffi.fixture.futures.uniffiForeignFutureHandleCount

// CI variance under FFM-Panama is wider than the JNA-based upstream
// runtime, so use looser bounds. Tight-loop tests would be flaky on
// the slower macos-26 runners; these tolerances let real bugs through
// (e.g. async path going synchronous or a 10× slowdown) without
// flaking on JVM warmup.
private const val IMMEDIATE_MAX_MS = 100L
private const val APPROXIMATE_TOLERANCE_MS = 500L

private fun assertImmediate(actualMs: Long, label: String) {
    check(actualMs <= IMMEDIATE_MAX_MS) {
        "expected $label to return immediately, took ${actualMs}ms"
    }
}

private fun assertApproximate(actualMs: Long, expectedMs: Long, label: String) {
    // Lower bound: -50ms slack for clock-resolution drift.
    val lower = expectedMs - 50
    val upper = expectedMs + APPROXIMATE_TOLERANCE_MS
    check(actualMs in lower..upper) {
        "expected $label to take ~${expectedMs}ms (range $lower..$upper), took ${actualMs}ms"
    }
}

fun main() = runBlocking {
    // ── Init UniFFI machinery on first call so subsequent assertions
    // measure steady-state, not first-call MethodHandle-lookup cost.
    val initMs = measureTimeMillis { Futures.alwaysReady() }
    println("init time: ${initMs}ms")

    // ── alwaysReady: async fn returning Bool, completes immediately.
    runBlocking {
        val ms = measureTimeMillis {
            check(Futures.alwaysReady())
        }
        assertImmediate(ms, "alwaysReady")
    }

    // ── void: async fn returning Unit, completes immediately. The
    // generated method name `void` is escaped with backticks because
    // it's a Java keyword.
    runBlocking {
        val ms = measureTimeMillis {
            Futures.`void`()
        }
        assertImmediate(ms, "void")
    }

    // ── sleep: async fn that actually waits.
    runBlocking {
        val ms = measureTimeMillis {
            check(Futures.sleep(200))
        }
        assertApproximate(ms, 200, "sleep")
    }

    // ── Sequential vs concurrent: `async { }.await()` should overlap
    // both `sayAfter` calls (each ~100/200ms), so concurrent dispatch
    // completes in ≈max not ≈sum.
    runBlocking {
        val seqMs = measureTimeMillis {
            val a = Futures.sayAfter(100, "Alice")
            val b = Futures.sayAfter(200, "Bob")
            check(a == "Hello, Alice!")
            check(b == "Hello, Bob!")
        }
        assertApproximate(seqMs, 300, "sequential sayAfter")

        val concMs = measureTimeMillis {
            val a = async { Futures.sayAfter(100, "Alice") }
            val b = async { Futures.sayAfter(200, "Bob") }
            check(a.await() == "Hello, Alice!")
            check(b.await() == "Hello, Bob!")
        }
        assertApproximate(concMs, 200, "concurrent sayAfter")
    }

    // ── Async object methods.
    runBlocking {
        val megaphone = Futures.newMegaphone()
        val ms = measureTimeMillis {
            check(megaphone.sayAfter(200, "Alice") == "HELLO, ALICE!")
        }
        assertApproximate(ms, 200, "megaphone.sayAfter")
    }
    runBlocking {
        val megaphone = Futures.newMegaphone()
        val ms = measureTimeMillis {
            check(Futures.sayAfterWithMegaphone(megaphone, 200, "Alice") == "HELLO, ALICE!")
        }
        assertApproximate(ms, 200, "sayAfterWithMegaphone")
    }

    // ── Async constructor: `Megaphone.secondary()` is a companion-object
    // factory because Kotlin constructors can't be `suspend`.
    runBlocking {
        val megaphone = Megaphone.secondary()
        check(megaphone.sayAfter(1, "hi") == "HELLO, HI!")
    }

    // ── Async return of optional Object.
    runBlocking {
        check(Futures.asyncMaybeNewMegaphone(true) != null)
        check(Futures.asyncMaybeNewMegaphone(false) == null)
    }

    // ── Async methods on Rust-implemented trait interfaces.
    runBlocking {
        val traits = Futures.getSayAfterTraits()
        val ms = measureTimeMillis {
            check(traits[0].sayAfter(100, "Alice") == "Hello, Alice!")
            check(traits[1].sayAfter(100, "Bob") == "Hello, Bob!")
        }
        assertApproximate(ms, 200, "trait sayAfter (proc-macro)")
    }
    runBlocking {
        val traits = Futures.getSayAfterUdlTraits()
        val ms = measureTimeMillis {
            check(traits[0].sayAfter(100, "Alice") == "Hello, Alice!")
            check(traits[1].sayAfter(100, "Bob") == "Hello, Bob!")
        }
        assertApproximate(ms, 200, "trait sayAfter (UDL)")
    }

    // ── Foreign-implemented async trait passed back to Rust. Exercises
    // the full Kotlin→Rust→Kotlin async vtable, typed-error mapping,
    // and (last block) coroutine cancellation propagating into the
    // Rust-side foreign-future handle map.
    val parser = KotlinAsyncParser()
    runBlocking {
        check(Futures.asStringUsingTrait(parser, 1, 42) == "42")
        check(Futures.tryFromStringUsingTrait(parser, 1, "42") == 42)

        try {
            Futures.tryFromStringUsingTrait(parser, 1, "not-a-number")
            error("expected ParserException.NotAnInt")
        } catch (e: ParserException.NotAnInt) {
            // expected
        }

        try {
            Futures.tryFromStringUsingTrait(parser, 1, "force-unexpected-exception")
            error("expected ParserException.UnexpectedException")
        } catch (e: ParserException.UnexpectedException) {
            // expected
        }

        Futures.delayUsingTrait(parser, 1)

        try {
            Futures.tryDelayUsingTrait(parser, "one")
            error("expected ParserException.NotAnInt")
        } catch (e: ParserException.NotAnInt) {
            // expected
        }

        // ── Cancellation: cancelDelayUsingTrait launches a foreign
        // future then cancels it before the inner `delay()` would
        // complete. After waiting longer than the requested delay,
        // `completedDelays` must NOT have advanced.
        val before = parser.completedDelays
        Futures.cancelDelayUsingTrait(parser, 10)
        delay(100)
        check(parser.completedDelays == before) {
            "cancelled delay still completed: ${parser.completedDelays} vs $before"
        }

        // Foreign-future handle map should be empty after every
        // call has been resolved or cancelled — leak check.
        val leaked = uniffiForeignFutureHandleCount()
        check(leaked == 0) {
            "leaked $leaked foreign-future handles"
        }
    }

    // ── Tokio-runtime path: routed through a separate runtime in Rust.
    runBlocking {
        val ms = measureTimeMillis {
            check(Futures.sayAfterWithTokio(200, "Alice") == "Hello, Alice (with Tokio)!")
        }
        assertApproximate(ms, 200, "sayAfterWithTokio")
    }

    // ── Fallible async fn + method, both error-and-success paths.
    runBlocking {
        check(Futures.fallibleMe(false) == 42.toByte())
        var threw = false
        try {
            Futures.fallibleMe(true)
        } catch (e: Exception) {
            threw = true
        }
        check(threw) { "fallibleMe(true) should throw" }

        val megaphone = Futures.newMegaphone()
        check(megaphone.fallibleMe(false) == 42.toByte())
        threw = false
        try {
            megaphone.fallibleMe(true)
        } catch (e: Exception) {
            threw = true
        }
        check(threw) { "megaphone.fallibleMe(true) should throw" }

        // fallibleStruct returns Megaphone | MyException.
        Futures.fallibleStruct(false)
        try {
            Futures.fallibleStruct(true)
            error("fallibleStruct(true) should throw MyException")
        } catch (e: MyException) {
            // expected
        }
    }

    // ── Async record return.
    runBlocking {
        val record = Futures.newMyRecord("foo", 42)
        check(record.a == "foo")
        check(record.b == 42)
    }

    // ── Broken sleep: the upstream test exercises a Rust-side waker
    // bug regression where the same future's waker is fired twice.
    // Should not deadlock or panic; total time is the sum of two
    // sleeps plus a 100ms recovery wait between them.
    runBlocking {
        val ms = measureTimeMillis {
            Futures.brokenSleep(100, 0)
            Futures.sleep(100)
            Futures.brokenSleep(100, 100)
            Futures.sleep(200)
        }
        assertApproximate(ms, 500, "brokenSleep")
    }

    // ── Shared-resource lock + cancellation: the first `launch` holds
    // the lock for 5s but is cancelled at 50ms; the second call must
    // be able to acquire the lock immediately after cancellation
    // releases it. Without correct cancellation the second call
    // would hit the 1000ms timeout and throw.
    runBlocking {
        val ms = measureTimeMillis {
            val job = launch {
                Futures.useSharedResource(SharedResourceOptions(releaseAfterMs = 5000, timeoutMs = 100))
            }
            delay(50)
            job.cancel()
            Futures.useSharedResource(SharedResourceOptions(releaseAfterMs = 0, timeoutMs = 1000))
        }
        println("useSharedResource (cancelled): ${ms}ms")
    }

    // ── Same shape, no cancellation — sanity check that the happy
    // path works without contention.
    runBlocking {
        val ms = measureTimeMillis {
            Futures.useSharedResource(SharedResourceOptions(releaseAfterMs = 100, timeoutMs = 1000))
            Futures.useSharedResource(SharedResourceOptions(releaseAfterMs = 0, timeoutMs = 1000))
        }
        println("useSharedResource (uncancelled): ${ms}ms")
    }

    println("test_fixture_futures_kotlin: OK")
}

// Foreign implementation of the AsyncParser trait passed into Rust to
// validate the Kotlin→Rust→Kotlin async vtable.
private class KotlinAsyncParser : AsyncParser {
    var completedDelays: Int = 0

    override suspend fun asString(delayMs: Int, value: Int): String {
        delay(delayMs.toLong())
        return value.toString()
    }

    override suspend fun tryFromString(delayMs: Int, value: String): Int {
        delay(delayMs.toLong())
        if (value == "force-unexpected-exception") {
            throw RuntimeException("UnexpectedException")
        }
        return try {
            value.toInt()
        } catch (e: NumberFormatException) {
            throw ParserException.NotAnInt()
        }
    }

    override suspend fun delay(delayMs: Int) {
        delay(delayMs.toLong())
        completedDelays += 1
    }

    override suspend fun tryDelay(delayMs: String) {
        val parsed = try {
            delayMs.toLong()
        } catch (e: NumberFormatException) {
            throw ParserException.NotAnInt()
        }
        delay(parsed)
        completedDelays += 1
    }
}
