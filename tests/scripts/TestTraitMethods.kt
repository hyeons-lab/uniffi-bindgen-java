// Runtime test for the local `trait-methods-kt` fixture, which
// exercises `#[uniffi::export(Display, Eq, Ord, Hash)]` on records,
// objects, flat enums, errors, and non-flat enums. Each override is
// routed through Rust via FFI, so the Kotlin assertions verify both
// the toString/equals/hashCode/compareTo plumbing and the Rust-side
// custom impls (which compare by string field only, ignoring `i`).
import uniffi.trait_methods_kt.TraitEnum
import uniffi.trait_methods_kt.TraitErr
import uniffi.trait_methods_kt.TraitFlatEnum
import uniffi.trait_methods_kt.TraitObj
import uniffi.trait_methods_kt.TraitRec

fun main() {
    // TraitRec: Display says "TraitRec(s={s})"; eq/ord/hash ignore `i`.
    check(TraitRec("yo", 1).toString() == "TraitRec(s=yo)")
    check(TraitRec("yo", 1) == TraitRec("yo", 99))
    check(TraitRec("yo", 1) != TraitRec("hi", 1))
    check(TraitRec("yo", 1).hashCode() == TraitRec("yo", 99).hashCode())
    check(TraitRec("a", 0).compareTo(TraitRec("yo", 0)) < 0)
    check(TraitRec("yo", 0).compareTo(TraitRec("a", 0)) > 0)

    // TraitObj: Display says "TraitObj({val})"; eq/ord/hash by val.
    TraitObj("yo").use { o ->
        check(o.toString() == "TraitObj(yo)")
        TraitObj("yo").use { same ->
            check(o == same)
            check(o.hashCode() == same.hashCode())
        }
        TraitObj("z").use { z ->
            check(o != z)
            check(o.compareTo(z) < 0)
            check(z.compareTo(o) > 0)
        }
    }

    // TraitFlatEnum: Display only; eq/hash/compareTo are Kotlin-default
    // since `enum class` finals them.
    check(TraitFlatEnum.ALPHA.toString() == "TraitFlatEnum::alpha")
    check(TraitFlatEnum.BETA.toString() == "TraitFlatEnum::beta")
    check(TraitFlatEnum.GAMMA.toString() == "TraitFlatEnum::gamma")

    // TraitErr: eq/ord/hash by discriminant only. Display via thiserror.
    val net1: TraitErr = TraitErr.Network("a")
    val net2: TraitErr = TraitErr.Network("b")
    val srv1: TraitErr = TraitErr.Server(500, "x")
    val srv2: TraitErr = TraitErr.Server(503, "y")
    check(net1 == net2)
    check(net1.hashCode() == net2.hashCode())
    check(srv1 == srv2)
    check(net1 != srv1)
    check(net1.compareTo(srv1) < 0)
    check(srv1.compareTo(net1) > 0)
    check(net1.toString() == "network: a")
    check(srv1.toString() == "server 500: x")

    // TraitEnum: variant-specific Display; eq/hash by discriminant only.
    val none: TraitEnum = TraitEnum.None
    val s1: TraitEnum = TraitEnum.S("hi")
    val s2: TraitEnum = TraitEnum.S("there")
    val p1: TraitEnum = TraitEnum.P(1, 2)
    check(none.toString() == "TraitEnum::None")
    check(s1.toString() == "TraitEnum::S(\"hi\")")
    check(p1.toString() == "TraitEnum::P(1, 2)")
    check(s1 == s2)
    check(s1.hashCode() == s2.hashCode())
    check(none != s1)
    check(none.compareTo(s1) < 0)
    check(s1.compareTo(p1) < 0)
}
