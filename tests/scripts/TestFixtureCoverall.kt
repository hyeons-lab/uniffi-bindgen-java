// PR 3a strategic subset: records (all scalar types + optionals via
// createSomeDict / createNoneDict), Coveralls constructor + getName +
// strongCount, one simple typed-error throw (maybeThrow ->
// CoverallException.TooManyHoles), and Arc lifecycle via
// Coverall.getNumAlive(). Traits, complex errors, and async land in
// PRs 3b / 3c.

import uniffi.coverall.Coverall
import uniffi.coverall.CoverallException
import uniffi.coverall.Coveralls
import uniffi.coverall.SimpleDict

fun main() {
    // ── createSomeDict: every scalar field populated, optionals
    // present (non-null). Nested Coveralls in the dict need manual
    // close (data class is not AutoCloseable; Kotlin doesn't get the
    // Java try-with-resources cascade for free).
    val someDict = Coverall.createSomeDict()
    try {
        check(someDict.text == "text")
        check(someDict.maybeText == "maybe_text")
        check(someDict.someBytes.contentEquals("some_bytes".toByteArray(Charsets.UTF_8)))
        // `!!` on the optional makes "must be present" an explicit
        // precondition rather than relying on the nullable
        // `ByteArray?.contentEquals` extension to silently return false.
        check(someDict.maybeSomeBytes!!.contentEquals("maybe_some_bytes".toByteArray(Charsets.UTF_8)))
        check(someDict.aBool)
        check(someDict.maybeABool == false)
        check(someDict.unsigned8 == 1.toByte())
        check(someDict.maybeUnsigned8 == 2.toByte())
        check(someDict.unsigned16 == 3.toShort())
        check(someDict.maybeUnsigned16 == 4.toShort())
        // u64::MAX is bit-equivalent to -1L (signed two's complement).
        check(someDict.unsigned64 == -1L)
        check(someDict.maybeUnsigned64 == 0L)
        check(someDict.signed8 == 8.toByte())
        check(someDict.maybeSigned8 == 0.toByte())
        check(someDict.signed64 == Long.MAX_VALUE)
        check(someDict.maybeSigned64 == 0L)

        check(almostEquals(someDict.float32, 1.2345f))
        check(almostEquals(someDict.maybeFloat32!!, 22.0f / 7.0f))
        check(almostEquals(someDict.float64, 0.0))
        check(almostEquals(someDict.maybeFloat64!!, 1.0))

        // Spot-check the nested Coveralls (single-field; full
        // list/map traversal is deferred to PR 3b).
        check(someDict.coveralls!!.getName() == "some_dict")
        check(Coverall.getNumAlive() == 5L)
    } finally {
        // Close every nested Coveralls handle the dict carries.
        someDict.coveralls?.close()
        someDict.coverallsList.forEach { it?.close() }
        someDict.coverallsMap.values.forEach { it?.close() }
    }
    check(Coverall.getNumAlive() == 0L)

    // ── createNoneDict: scalars populated, every optional null, no
    // nested Coveralls.
    val noneDict = Coverall.createNoneDict()
    try {
        check(noneDict.text == "text")
        check(noneDict.maybeText == null)
        check(noneDict.someBytes.contentEquals("some_bytes".toByteArray(Charsets.UTF_8)))
        check(noneDict.maybeSomeBytes == null)
        check(noneDict.aBool)
        check(noneDict.maybeABool == null)
        check(noneDict.unsigned8 == 1.toByte())
        check(noneDict.maybeUnsigned8 == null)
        check(noneDict.unsigned16 == 3.toShort())
        check(noneDict.maybeUnsigned16 == null)
        check(noneDict.unsigned64 == -1L)
        check(noneDict.maybeUnsigned64 == null)
        check(noneDict.signed8 == 8.toByte())
        check(noneDict.maybeSigned8 == null)
        check(noneDict.signed64 == Long.MAX_VALUE)
        check(noneDict.maybeSigned64 == null)
        check(almostEquals(noneDict.float32, 1.2345f))
        check(noneDict.maybeFloat32 == null)
        check(almostEquals(noneDict.float64, 0.0))
        check(noneDict.maybeFloat64 == null)
        check(noneDict.coveralls == null)
        check(Coverall.getNumAlive() == 0L)
    } finally {
        // No nested handles to close (everything's null), but keep
        // the symmetric finally for clarity.
    }
    check(Coverall.getNumAlive() == 0L)

    // ── Coveralls primary constructor + getName + strongCount.
    Coveralls("test_arcs").use { coveralls ->
        check(Coverall.getNumAlive() == 1L)
        check(coveralls.getName() == "test_arcs")
        // Two refs: one held by the foreign-language wrapper, one
        // borrowed for the duration of the strongCount() call.
        check(coveralls.strongCount() == 2L)
    }
    check(Coverall.getNumAlive() == 0L)

    // ── Typed-error throw. CoverallException.TooManyHoles is the
    // only flat-error variant in this fixture.
    Coveralls("test_simple_errors").use { coveralls ->
        try {
            coveralls.maybeThrow(true)
            error("Expected maybeThrow(true) to throw CoverallException.TooManyHoles")
        } catch (e: CoverallException.TooManyHoles) {
            check(e.message == "The coverall has too many holes")
        }
    }
    check(Coverall.getNumAlive() == 0L)
}

private fun almostEquals(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.000001f
private fun almostEquals(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.000001
