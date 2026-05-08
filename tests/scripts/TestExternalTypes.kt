// Cross-crate type imports — round-trips every shape exposed by
// uniffi-fixture-ext-types, which spans 5 crates rendered into 5
// separate Kotlin packages:
//
//   • imported_types_lib  — primary fixture entry point
//   • imported_types_sublib — secondary lib re-exporting types
//   • uniffi_one_ns       — UniffiOneType / Enum / Trait / Errors
//   • ext_types_custom    — Guid / Ouid / HandleU8 customs
//   • customtypes         — Url custom-type (lifted as java.net.URL
//                           per the example's own [bindings.kotlin]
//                           config)
//
// Coverage:
//   • CombinedType round-trip (record with cross-crate fields).
//   • Cross-crate Object types: ObjectsType, SubLibType, getTraitImpl().
//   • UniffiOneTrait: foreign Kotlin impl injected into Rust via
//     invokeUniffiOneTrait — exercises the cross-crate vtable
//     initialization (#2343).
//   • URL custom-type: get / getMaybe / getList / getOptionalList.
//   • Guid / Ouid (data-class-wrapped customs) and HandleU8 (Byte).
//   • UniffiOneType / UniffiOneEnum: typed nullable + List<T?>.
//   • UniffiOneException (typed-error enum, defined in uniffi_one):
//     exercises cross-crate package-anchored helpers (PR #57).
//   • UniffiOneErrorInterface (Object-as-error, defined in
//     uniffi_one): exercises both the `is_error` Object branch
//     (PR #57) and the helpers_prefix qualification (PR #57).
//   • ExternalCrateInterface (Object from a downstream crate).
//   • BindingRenamedType → KotlinRenamedType (per-binding rename).
//
// Mirrors upstream uniffi-rs's `test_imported_types.kts` adapted
// for this backend's namespace-qualified dispatch and signed
// integer types (Byte for u8, not UByte).

import customtypes.Url
import uniffi.ext_types_custom.ExtTypesCustom
import uniffi.ext_types_custom.Guid
import uniffi.ext_types_custom.HandleU8
import uniffi.ext_types_custom.Ouid
import uniffi.imported_types_lib.ImportedTypesLib
import uniffi.imported_types_sublib.ImportedTypesSublib
import uniffi.uniffi_one_ns.KotlinRenamedType
import uniffi.uniffi_one_ns.UniffiOneEnum
import uniffi.uniffi_one_ns.UniffiOneException
import uniffi.uniffi_one_ns.UniffiOneTrait
import uniffi.uniffi_one_ns.UniffiOneType

// Foreign Kotlin implementation of `UniffiOneTrait` — passed back
// to Rust via `invokeUniffiOneTrait`. The cross-crate dimension
// (Rust side defines the trait in `uniffi-one`, Kotlin foreign
// impl lives here) is upstream regression #2343 (vtable init for
// cross-crate callback interfaces).
private class KtUniffiOneImpl : UniffiOneTrait {
    override fun hello(): String = "Hello from Kotlin"
}

fun main() {
    // ── Cross-crate vtable-init regression.
    check(ImportedTypesLib.invokeUniffiOneTrait(KtUniffiOneImpl()) == "Hello from Kotlin")

    // ── CombinedType: record with fields from 3 different crates.
    // Null-input gives a default per the fixture impl.
    val ct = ImportedTypesLib.getCombinedType(null)
    check(ct.uot.sval == "hello")
    check(ct.guid.value == "a-guid")
    check(ct.url.value == java.net.URI("http://example.com/").toURL())

    val ct2 = ImportedTypesLib.getCombinedType(ct)
    check(ct == ct2)

    // ── ObjectsType: record with optional cross-crate Object/Trait
    // fields. Null-input gives both fields null per fixture impl.
    val ot = ImportedTypesLib.getObjectsType(null)
    check(ot.maybeInterface == null)
    check(ot.maybeTrait == null)
    check(ImportedTypesLib.getUniffiOneTrait(null) == null)

    // ── SubLibType from the sub-lib crate.
    check(ImportedTypesSublib.getSubType(null).maybeInterface == null)
    check(ImportedTypesSublib.getTraitImpl().hello() == "sub-lib trait impl says hello")

    // ── URL custom-type — `examples/custom-types/uniffi.toml`'s
    // `[bindings.kotlin.custom_types.Url]` lifts as `java.net.URL`,
    // so `Url(value: URL)` is the data-class shape.
    val url = Url(java.net.URI("http://example.com/").toURL())
    check(ImportedTypesLib.getUrl(url) == url)
    check(ImportedTypesLib.getMaybeUrl(url)!! == url)
    check(ImportedTypesLib.getMaybeUrl(null) == null)
    check(ImportedTypesLib.getUrls(listOf(url)) == listOf(url))
    check(ImportedTypesLib.getMaybeUrls(listOf(url, null)) == listOf(url, null))

    // ── Guid / Ouid (data-class-wrapped customs).
    check(ExtTypesCustom.getGuid(Guid("guid")).value == "guid")
    check(ExtTypesCustom.getOuid(Ouid("ouid")).value == "ouid")
    check(ImportedTypesLib.getImportedOuid(Ouid("ouid")).value == "ouid")

    // ── HandleU8: data-class wrapping a u8 (signed Byte). Null
    // input gives default `HandleU8(3)` per fixture impl.
    check(ImportedTypesLib.getImportedHandleU8(null).value == 3.toByte())

    // ── UniffiOneType: cross-crate record. Round-trip equality +
    // nullable + list permutations. ImportedTypesLib re-exposes
    // these via namespace fns (re-exported from uniffi-one).
    val uot = UniffiOneType("hello")
    check(ImportedTypesLib.getUniffiOneType(uot) == uot)
    check(ImportedTypesLib.getMaybeUniffiOneType(uot)!! == uot)
    check(ImportedTypesLib.getMaybeUniffiOneType(null) == null)
    check(ImportedTypesLib.getUniffiOneTypes(listOf(uot)) == listOf(uot))
    check(ImportedTypesLib.getMaybeUniffiOneTypes(listOf(uot, null)) == listOf(uot, null))

    // ── UniffiOneEnum (cross-crate flat enum).
    val uoe = UniffiOneEnum.ONE
    check(ImportedTypesLib.getUniffiOneEnum(uoe) == uoe)
    check(ImportedTypesLib.getMaybeUniffiOneEnum(uoe)!! == uoe)
    check(ImportedTypesLib.getMaybeUniffiOneEnum(null) == null)
    check(ImportedTypesLib.getUniffiOneEnums(listOf(uoe)) == listOf(uoe))
    check(ImportedTypesLib.getMaybeUniffiOneEnums(listOf(uoe, null)) == listOf(uoe, null))

    // ── UniffiOneException (typed-error enum, cross-crate). Tests
    // PR #57's `helpers_prefix` qualification — without it, the
    // local `UniffiHelpers.uniffiRustCallVoidWithError` would
    // reject the foreign-package `UniffiOneExceptionErrorHandler`.
    try {
        ImportedTypesLib.throwUniffiOneError()
        error("Expected throwUniffiOneError() to throw UniffiOneException.Oops")
    } catch (e: UniffiOneException) {
        check(e is UniffiOneException.Oops)
        e as UniffiOneException.Oops
        check(e.v1 == "oh no")
    }

    // ── UniffiOneErrorInterface (Object-as-error, cross-crate).
    // Tests both PR #57 fixes: (a) `is_error` Object branch in
    // `ObjectTemplate.kt` extends `kotlin.Exception()` so
    // `@Throws(...)` typechecks; (b) helpers_prefix qualification
    // for the cross-crate handler reference.
    try {
        ImportedTypesLib.throwUniffiOneErrorInterface()
        error("Expected throwUniffiOneErrorInterface() to throw UniffiOneErrorInterface")
    } catch (e: uniffi.uniffi_one_ns.UniffiOneErrorInterface) {
        check(e.message() == "interface oops")
    }

    // ── Cross-crate nested record (`ecd` field of CombinedType is
    // an `ExternalCrateDictionary` from a separate crate).
    check(ct.ecd.sval == "ecd")
    check(ImportedTypesLib.getExternalCrateInterface("foo").value() == "foo")

    // ── BindingRenamedType: in the upstream fixture this is
    // `BindingRenamedType` with `value: String`. The per-binding
    // config in `uniffi-one`'s uniffi.toml renames the type to
    // `KotlinRenamedType` and the field to `kotlinValue`.
    val renamed = ImportedTypesLib.getBindingRenamedType("external_rename_test")
    check(renamed is KotlinRenamedType)
    check(renamed.kotlinValue == "external_rename_test")

    println("test_external_types_kotlin: OK")
}
