// Runtime test for the upstream `proc-macro` fixture, exercising
// the `#[uniffi::export]` proc-macro path on records, methods on
// records, methods on flat enums, methods on non-flat enums, and
// objects implementing a separately-declared `Trait`.
import uniffi.fixture.proc_macro.MaybeBool
import uniffi.fixture.proc_macro.MixedEnum
import uniffi.fixture.proc_macro.One
import uniffi.fixture.proc_macro.StructWithTrait

fun main() {
    // Record method via proc-macro impl.
    check(One(42).getInnerValue() == 42)

    // Flat enum methods cycle TRUE → FALSE → UNCERTAIN → TRUE.
    check(MaybeBool.TRUE.next() == MaybeBool.FALSE)
    check(MaybeBool.FALSE.next() == MaybeBool.UNCERTAIN)
    check(MaybeBool.UNCERTAIN.next() == MaybeBool.TRUE)

    // Non-flat (sealed) enum methods. `isNotNone` is true for every
    // variant except `None`.
    check(MixedEnum.String("hello").isNotNone())
    check(MixedEnum.Int(123L).isNotNone())
    check(!MixedEnum.None.isNotNone())

    // Object implementing a separately-declared Trait. The Rust
    // `impl Trait for StructWithTrait { fn concat_strings ... }`
    // surfaces as a direct method on `StructWithTrait`. (Note: the
    // Kotlin codegen doesn't yet emit `StructWithTrait : Trait` —
    // that's tracked as a separate codegen gap. The methods are
    // dispatched correctly even without the interface declaration.)
    StructWithTrait("test").use { swt ->
        check(swt.concatStrings("foo", "bar") == "test: foobar")
    }
}
