// Test that `omit_checksums = true` works correctly on the Kotlin
// backend. This fixture's `uniffi-extras.toml` flips the flag for both
// `[bindings.java]` and `[bindings.kotlin]`; if the codegen path is
// wired correctly, the generated bindings have neither
// `uniffiCheckApiChecksums()` nor an init-time call to it. The fact
// that the bindings still load and basic operations work is the
// regression signal that the gating remains functional.
import org.mozilla.uniffi.example.arithmetic.Arithmetic
import org.mozilla.uniffi.example.arithmetic.ArithmeticException

fun main() {
    check(Arithmetic.add(2L, 4L) == 6L)
    check(Arithmetic.sub(4L, 2L) == 2L)
    check(Arithmetic.div(8L, 4L) == 2L)
    check(Arithmetic.equal(2L, 2L))
    check(!Arithmetic.equal(2L, 3L))

    try {
        Arithmetic.sub(0L, 2L)
        error("Subtraction causing negative should throw IntegerOverflow")
    } catch (e: ArithmeticException.IntegerOverflow) {
        // expected
    }
}
