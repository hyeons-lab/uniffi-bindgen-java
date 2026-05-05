// Runtime test for the upstream `rename` fixture. Exercises both
// rename surfaces:
//   - Proc-macro `#[uniffi(name = "...")]` renames (cross-language):
//     `RenamedRecord`, `RenamedEnum`, `RenamedObject`, `Trait`,
//     `renamedFunction`, `renamedConstructor`, `renamedMethod`,
//     `renamedTraitMethod`.
//   - The Kotlin block of `[bindings.kotlin.rename]` in the fixture's
//     `uniffi.toml`: `BindingFoo` types renamed to `KtFoo`,
//     `binding_function` → `ktFunction`, `kotlin_method`/`kotlin_trait_method`
//     pass through fn_name's snake → camelCase.
import uniffi.uniffi_fixture_rename.KtEnum
import uniffi.uniffi_fixture_rename.KtException
import uniffi.uniffi_fixture_rename.KtObject
import uniffi.uniffi_fixture_rename.KtRecord
import uniffi.uniffi_fixture_rename.RenamedEnum
import uniffi.uniffi_fixture_rename.RenamedObject
import uniffi.uniffi_fixture_rename.RenamedRecord
import uniffi.uniffi_fixture_rename.UniffiFixtureRename

fun main() {
    // Proc-macro renames.
    val record = RenamedRecord(42)
    check(record.item == 42)

    val enum1: RenamedEnum = RenamedEnum.RenamedVariant
    val enum2: RenamedEnum = RenamedEnum.Record(record)
    check(enum1 is RenamedEnum.RenamedVariant)
    check(enum2 is RenamedEnum.Record)

    val result = UniffiFixtureRename.renamedFunction(record)
    check(result is RenamedEnum.Record)
    check((result as RenamedEnum.Record).v1.item == 42)

    RenamedObject.renamedConstructor(123).use { obj ->
        check(obj.renamedMethod() == 123)
    }

    UniffiFixtureRename.createTraitImpl(5).use { traitImpl ->
        check(traitImpl.renamedTraitMethod(10) == 50)
    }

    // Kotlin TOML renames.
    val ktRecord = KtRecord(123)
    check(ktRecord.kotlinItem == 123)

    val ktResult = UniffiFixtureRename.ktFunction(ktRecord)
    check(ktResult is KtEnum.KotlinRecord)

    val ktEnum1: KtEnum = KtEnum.KotlinVariantA
    val ktEnum2: KtEnum = KtEnum.KotlinRecord(ktRecord)
    check(ktEnum1 is KtEnum.KotlinVariantA)
    check(ktEnum2 is KtEnum.KotlinRecord)

    // Renamed error type.
    try {
        UniffiFixtureRename.ktFunction(null)
        error("Should have thrown KtException")
    } catch (e: KtException.KotlinSimple) {
        // expected
    }

    // Renamed object: arg also renamed (kotlinArg).
    KtObject(100).use { ktObj ->
        check(ktObj.kotlinMethod(10) == 110)
    }

    UniffiFixtureRename.createBindingTraitImpl(3).use { ktTraitImpl ->
        check(ktTraitImpl.kotlinTraitMethod(4) == 12)
    }
}
