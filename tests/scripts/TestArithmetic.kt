import org.mozilla.uniffi.example.arithmetic.Arithmetic
import org.mozilla.uniffi.example.arithmetic.ArithmeticException
import org.mozilla.uniffi.example.arithmetic.InternalException

fun main() {
    check(Arithmetic.add(2L, 4L) == 6L)
    check(Arithmetic.add(4L, 8L) == 12L)

    try {
        Arithmetic.sub(0L, 2L)
        error("Should have thrown an IntegerOverflow exception!")
    } catch (e: ArithmeticException.IntegerOverflow) {
        // expected
    }

    check(Arithmetic.sub(4L, 2L) == 2L)
    check(Arithmetic.sub(8L, 4L) == 4L)

    check(Arithmetic.div(8L, 4L) == 2L)

    try {
        Arithmetic.div(8L, 0L)
        error("Should have panicked when dividing by zero")
    } catch (e: InternalException) {
        // expected — Rust-side panic surfaces as InternalException
    }

    check(Arithmetic.equal(2L, 2L))
    check(Arithmetic.equal(4L, 4L))
    check(!Arithmetic.equal(2L, 4L))
    check(!Arithmetic.equal(4L, 8L))
}
