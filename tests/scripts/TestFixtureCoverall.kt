// Coverall round-trip — full coverage across PRs 3a/3b/3c.
//
// PR 3a: records (all scalar types + optionals via createSomeDict /
// createNoneDict), Coveralls constructor + getName + strongCount,
// CoverallException.TooManyHoles, Arc lifecycle.
//
// PR 3b: ComplexException variants with payload data, Getters trait
// implemented in Rust (passed back to Kotlin) AND in Kotlin (passed
// into Rust + called back), NodeTrait round-trip with both
// Rust- and Kotlin-implementing nodes.
//
// PR 3c: async namespace function via runBlocking, Arc-sharing
// lifecycle (cloneMe / takeOther / takeOtherFallible /
// takeOtherPanic / falliblePanic), and FalliblePatch — both the
// always-fail primary constructor and the always-fail alternate
// `secondary()` companion factory.

import kotlinx.coroutines.runBlocking
import uniffi.coverall.ComplexException
import uniffi.coverall.Coverall
import uniffi.coverall.CoverallException
import uniffi.coverall.Coveralls
import uniffi.coverall.FalliblePatch
import uniffi.coverall.Getters
import uniffi.coverall.NodeTrait
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

    // ── ComplexException: variant-with-data error. Each input byte
    // selects a different variant; byte 4 is the panic path
    // (surfaces as InternalException). Each branch verifies both
    // type discrimination and field values.
    Coveralls("test_complex_errors").use { coveralls ->
        // `maybeThrowComplex` takes a `Byte`. Int literals fit
        // in Byte's positive range here, but explicit `.toByte()`
        // matches the rest of the file's convention.
        check(coveralls.maybeThrowComplex(0.toByte()))

        try {
            coveralls.maybeThrowComplex(1.toByte())
            error("Expected ComplexException.OsException")
        } catch (e: ComplexException.OsException) {
            check(e.code == 10.toShort())
            check(e.extendedCode == 20.toShort())
        }

        try {
            coveralls.maybeThrowComplex(2.toByte())
            error("Expected ComplexException.PermissionDenied")
        } catch (e: ComplexException.PermissionDenied) {
            check(e.reason == "Forbidden")
        }

        try {
            coveralls.maybeThrowComplex(3.toByte())
            error("Expected ComplexException.UnknownException")
        } catch (e: ComplexException.UnknownException) {
            // No payload to assert; reaching the catch is the assertion.
        }

        try {
            coveralls.maybeThrowComplex(4.toByte())
            error("Expected InternalException for the panic path")
        } catch (e: uniffi.coverall.InternalException) {
            // Rust panic surfaces as InternalException.
        }
    }
    check(Coverall.getNumAlive() == 0L)

    // ── Getters trait, Rust-implemented side. `makeRustGetters()`
    // returns a Rust-backed `Getters` impl that round-trips through
    // `testGetters(g: Getters)` (sanity check + opaque exercise of
    // the FFI vtable on the way out).
    val rustGetters = Coverall.makeRustGetters()
    Coverall.testGetters(rustGetters)
    exerciseGetters(rustGetters)
    // Rust-backed `Getters` is a Coveralls-like AutoCloseable wrapper.
    (rustGetters as AutoCloseable).close()

    // ── Getters trait, Kotlin-implemented side. The same suite of
    // calls, but the Kotlin object is now passing INTO Rust and
    // being called BACK via the foreign-vtable upcall path. Catches
    // any divergence between the two implementations.
    val kotlinGetters = KotlinGetters()
    Coverall.testGetters(kotlinGetters)
    exerciseGetters(kotlinGetters)

    // ── NodeTrait. `getTraits()` returns a list of Rust-backed
    // NodeTrait wrappers. Set parents on them, then thread a
    // Kotlin-backed NodeTrait into the same parent chain to verify
    // both directions of the FFI work.
    val traits = Coverall.getTraits()
    try {
        check(traits[0].name() == "node-1")
        check(traits[1].name() == "node-2")
        // Rust-side parent assignment.
        traits[0].setParent(traits[1])
        check(Coverall.ancestorNames(traits[0]) == listOf("node-2"))
        check(Coverall.ancestorNames(traits[1]).isEmpty())
        check(traits[0].getParent()?.name() == "node-2")

        // Kotlin-implemented parent in the chain.
        val kotlinNode = KotlinNode("node-kt")
        traits[1].setParent(kotlinNode)
        check(Coverall.ancestorNames(traits[0]) == listOf("node-2", "node-kt"))
        check(Coverall.ancestorNames(traits[1]) == listOf("node-kt"))
        check(Coverall.ancestorNames(kotlinNode).isEmpty())

        // Detach + re-attach from the Kotlin side. This catches
        // dangling-reference bugs in the upcall path.
        traits[1].setParent(null)
        kotlinNode.setParent(traits[0])
        check(Coverall.ancestorNames(kotlinNode) == listOf("node-1", "node-2"))

        // Drop all parents to release Rust-side strong refs.
        kotlinNode.setParent(null)
        traits[0].setParent(null)
    } finally {
        traits.forEach { (it as AutoCloseable).close() }
    }

    // ── async namespace function via `runBlocking`. `asyncBool` is
    // a non-throwing async fn returning Bool; routes through
    // `UniffiAsyncHelpers.uniffiRustCallAsync` and the i8 future
    // poll/complete/free triple. Tests both true and false to
    // ensure the boolean lift round-trips correctly.
    runBlocking {
        check(Coverall.asyncBool(true))
        check(!Coverall.asyncBool(false))
    }
    check(Coverall.getNumAlive() == 0L)

    // ── Arc-sharing lifecycle. `cloneMe` produces a new wrapper
    // around the same Rust Arc (count goes up); `takeOther` stashes
    // the Arc on the Rust side (another count up). Verifies the
    // ref-count protocol against `Coverall.getNumAlive()` and
    // `coveralls.strongCount()`.
    Coveralls("test_return_objects").use { coveralls ->
        check(Coverall.getNumAlive() == 1L)
        check(coveralls.strongCount() == 2L)
        coveralls.cloneMe().use { c2 ->
            check(c2.getName() == coveralls.getName())
            check(Coverall.getNumAlive() == 2L)
            check(c2.strongCount() == 2L)

            coveralls.takeOther(c2)
            // Same number of distinct objects alive but `c2` has an
            // extra ref count from the stash on the Rust side.
            check(Coverall.getNumAlive() == 2L)
            check(coveralls.strongCount() == 2L)
            check(c2.strongCount() == 3L)
        }
        // c2's Kotlin wrapper is closed but Rust still holds the
        // Arc via the takeOther stash.
        check(Coverall.getNumAlive() == 2L)
    }
    // Closing the outer wrapper releases the stashed Arc; both
    // objects drop.
    check(Coverall.getNumAlive() == 0L)

    // ── Throwing-takeOther variants. `takeOtherFallible` always
    // throws CoverallException.TooManyHoles; `takeOtherPanic` and
    // `falliblePanic` always panic on the Rust side, surfacing as
    // InternalException.
    Coveralls("test_throwing_take_other").use { coveralls ->
        try {
            coveralls.takeOtherFallible()
            error("Expected CoverallException.TooManyHoles")
        } catch (_: CoverallException.TooManyHoles) {
            // expected
        }
        try {
            coveralls.takeOtherPanic("expected panic: with an arc!")
            error("Expected InternalException")
        } catch (_: uniffi.coverall.InternalException) {
            // expected
        }
        try {
            coveralls.falliblePanic("Expected panic in a fallible function!")
            error("Expected InternalException")
        } catch (_: uniffi.coverall.InternalException) {
            // expected
        }
    }
    check(Coverall.getNumAlive() == 0L)

    // ── FalliblePatch. Both the primary constructor `FalliblePatch()`
    // and the alternate `FalliblePatch.secondary()` always throw on
    // the Rust side. Each is annotated `@Throws(CoverallException)`
    // by the codegen, so the catch type is required.
    try {
        FalliblePatch()
        error("Expected primary FalliblePatch() to throw")
    } catch (_: CoverallException) {
        // expected
    }
    try {
        FalliblePatch.secondary()
        error("Expected FalliblePatch.secondary() to throw")
    } catch (_: CoverallException) {
        // expected
    }
}

// Shared exerciser for a representative subset of the `Getters`
// trait, used against both the Rust-backed impl and the Kotlin
// one. Covers the throwing paths (typed CoverallException +
// typed ComplexException), the uppercase/optional behaviour
// switch, and value passthrough. Skips `getList` and
// `getNothing` — those are validated implicitly by
// `Coverall.testGetters(g)` calling them on the Rust side.
private fun exerciseGetters(g: Getters) {
    check(g.getBool(true, true) == false)
    check(g.getBool(true, false))
    check(g.getString("hello", false) == "hello")
    check(g.getString("hello", true) == "HELLO")
    check(g.getOption("hello", true) == "HELLO")
    check(g.getOption("", true) == null)
    try {
        g.getString("too-many-holes", true)
        error("Expected CoverallException.TooManyHoles")
    } catch (e: CoverallException.TooManyHoles) {
        // expected
    }
    try {
        g.getOption("os-error", true)
        error("Expected ComplexException.OsException")
    } catch (e: ComplexException.OsException) {
        check(e.code == 100.toShort())
        check(e.extendedCode == 200.toShort())
    }
    try {
        g.getOption("unknown-error", true)
        error("Expected ComplexException.UnknownException")
    } catch (_: ComplexException.UnknownException) {
        // expected
    }
}

// Kotlin-side `Getters` impl. When passed into Rust (e.g. via
// `testGetters`) it's looked up by handle and dispatched through
// the foreign vtable; same instance can also be exercised
// directly.
private class KotlinGetters : Getters {
    override fun getBool(v: Boolean, arg2: Boolean): Boolean = v != arg2

    override fun getString(v: String, arg2: Boolean): String =
        when (v) {
            "too-many-holes" -> throw CoverallException.TooManyHoles("too many holes")
            "unexpected-error" -> throw RuntimeException("unexpected error")
            else -> if (arg2) v.uppercase() else v
        }

    override fun getOption(v: String, arg2: Boolean): String? =
        when (v) {
            "os-error" -> throw ComplexException.OsException(100.toShort(), 200.toShort())
            "unknown-error" -> throw ComplexException.UnknownException()
            else ->
                if (arg2) {
                    if (v.isNotEmpty()) v.uppercase() else null
                } else {
                    v
                }
        }

    override fun getList(v: IntArray, arg2: Boolean): IntArray = if (arg2) v else IntArray(0)

    override fun getNothing(v: String) {}

    override fun roundTripObject(coveralls: Coveralls): Coveralls = coveralls
}

// Kotlin-side `NodeTrait` impl. State is kept in a Kotlin field;
// `getParent()` returns whatever `setParent()` last stashed.
private class KotlinNode(private val nodeName: String) : NodeTrait {
    private var currentParent: NodeTrait? = null

    override fun name(): String = nodeName
    override fun setParent(parent: NodeTrait?) {
        currentParent = parent
    }
    override fun getParent(): NodeTrait? = currentParent
    override fun strongCount(): Long = 0L
}

private fun almostEquals(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.000001f
private fun almostEquals(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.000001
