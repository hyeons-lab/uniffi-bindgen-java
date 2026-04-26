/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

//! Characterization snapshots for the Java backend.
//!
//! Captures the full set of generated `.java` files for a small set of
//! representative fixtures. The snapshots act as a regression detector
//! for the upcoming `LangOracle` extraction (P1.3) and marker-based
//! file splitting (P1.2): any unintentional change in generated output
//! surfaces as a snapshot diff that requires explicit acceptance.
//!
//! Review with `cargo insta review`; accept all with `cargo insta accept`.

use anyhow::Result;
use camino::{Utf8Path, Utf8PathBuf};
use std::fs;
use uniffi_bindgen::{BindgenLoader, BindgenPaths};
use uniffi_bindgen_java::{GenerateOptions, Language, generate};
use uniffi_testing::UniFFITestHelper;

fn snapshot_fixture(fixture_name: &str, snapshot_name: &str) -> Result<()> {
    snapshot_fixture_for(fixture_name, snapshot_name, Language::Java, "java")
}

fn snapshot_fixture_for(
    fixture_name: &str,
    snapshot_name: &str,
    language: Language,
    extension: &str,
) -> Result<()> {
    let test_helper = UniFFITestHelper::new(fixture_name)?;
    let key = Utf8Path::new(".")
        .join("tests")
        .join("snapshots")
        .join(snapshot_name);
    let out_dir = test_helper.create_out_dir(env!("CARGO_TARGET_TMPDIR"), &key)?;
    let cdylib_path = test_helper.cdylib_path()?;

    let mut paths = BindgenPaths::default();
    paths.add_cargo_metadata_layer(false)?;
    let loader = BindgenLoader::new(paths);

    let mut options = GenerateOptions::new(cdylib_path, out_dir.clone());
    options.format = false;
    options.language = language;
    generate(&loader, &options)?;

    let combined = collect_generated_files(&out_dir, extension)?;

    let mut settings = insta::Settings::clone_current();
    settings.set_snapshot_path("snapshots");
    settings.set_prepend_module_to_snapshot(false);
    // Cargo embeds a 16-hex-char build hash in cdylib filenames
    // (e.g. `uniffi_coverall-6022c3c5ece67733`) that varies per machine
    // and per build. The hash leaks into generated `findLibraryName()`
    // bodies. Redact it so snapshots are reproducible across CI and dev.
    settings.add_filter(r"-[0-9a-f]{16}\b", "-CARGO_BUILD_HASH");
    settings.bind(|| {
        insta::assert_snapshot!(snapshot_name, combined);
    });
    Ok(())
}

fn collect_generated_files(out_dir: &Utf8PathBuf, extension: &str) -> Result<String> {
    let pattern = out_dir.join(format!("**/*.{extension}"));
    let mut entries: Vec<(String, String)> = Vec::new();
    for path in glob::glob(pattern.as_str())? {
        let path = path?;
        let rel = path
            .strip_prefix(out_dir.as_std_path())?
            .to_string_lossy()
            .replace('\\', "/");
        let content = fs::read_to_string(&path)?;
        entries.push((rel, content));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    let combined = entries
        .into_iter()
        .map(|(rel, content)| format!("// === FILE: {} ===\n{}", rel, content))
        .collect::<Vec<_>>()
        .join("\n");
    Ok(combined)
}

/// Smoke test for the P2 Kotlin dispatch path. Asserts that
/// `generate(.., Language::Kotlin)` runs the Kotlin codepath end-to-end
/// (config parsing, marker splitting, file-extension plumbing) and writes
/// at least one `.kt` file at the expected package-path directory. No
/// snapshot yet — Kotlin snapshots arrive in P3 once real templates land.
#[test]
fn kotlin_dispatch_smoke() -> Result<()> {
    let fixture = "uniffi-example-arithmetic";
    let test_helper = UniFFITestHelper::new(fixture)?;
    let key = Utf8Path::new(".").join("tests").join("kotlin-smoke");
    let out_dir = test_helper.create_out_dir(env!("CARGO_TARGET_TMPDIR"), &key)?;
    let cdylib_path = test_helper.cdylib_path()?;

    let mut paths = BindgenPaths::default();
    paths.add_cargo_metadata_layer(false)?;
    let loader = BindgenLoader::new(paths);

    let mut options = GenerateOptions::new(cdylib_path, out_dir.clone());
    options.language = Language::Kotlin;
    generate(&loader, &options)?;

    let kt_files: Vec<_> = glob::glob(out_dir.join("**/*.kt").as_str())?
        .filter_map(Result::ok)
        .collect();
    assert!(
        !kt_files.is_empty(),
        "expected at least one .kt file under {out_dir}, got none",
    );
    Ok(())
}

#[test]
fn snapshot_arithmetic() -> Result<()> {
    snapshot_fixture("uniffi-example-arithmetic", "arithmetic")
}

/// Kotlin-side snapshot for the arithmetic fixture. Captures the full
/// Kotlin runtime + namespace-function rendering so a change to either
/// backend without a paired update surfaces as a CI diff (per the
/// dual-backend maintenance strategy in the project plan). Post-P3g
/// this also exercises typed-error generation (`ArithmeticError →
/// ArithmeticException` + `ArithmeticExceptionErrorHandler`) since the
/// fixture's `add`/`sub` declare `[Throws=ArithmeticError]`.
#[test]
fn snapshot_arithmetic_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-example-arithmetic",
        "arithmetic_kotlin",
        Language::Kotlin,
        "kt",
    )
}

#[test]
fn snapshot_geometry() -> Result<()> {
    snapshot_fixture("uniffi-example-geometry", "geometry")
}

/// Kotlin-side snapshot for geometry. P3f adds `data class` records and
/// `T?` optionals — geometry exercises both (`Point` / `Line` records,
/// plus `intersection`'s `Point?` return). Coverall stays Java-only
/// until objects / callbacks / sequences land in P4+.
#[test]
fn snapshot_geometry_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-example-geometry",
        "geometry_kotlin",
        Language::Kotlin,
        "kt",
    )
}

#[test]
fn snapshot_coverall() -> Result<()> {
    snapshot_fixture("uniffi-fixture-coverall", "coverall")
}

/// Kotlin-side snapshot for the upstream `coverall` fixture — the
/// comprehensive smoke fixture that exercises every UniFFI surface
/// in one place: records with all scalar types + optionals, objects
/// (including `[Trait, WithForeign]`), flat & non-flat enums, flat
/// / rich / nested errors, sync & async traits, an async namespace
/// function, return-only types, `[ByRef]` arguments, and
/// `[Self=ByArc]` methods. Several of these combinations are not
/// exercised by any other Kotlin snapshot in this test suite, so
/// regressions in less-traveled template branches show up here
/// first. This snapshot catches Kotlin codegen drift without
/// paying the price of a full `kotlinc` round-trip; runtime smoke
/// coverage can be added separately as needed.
#[test]
fn snapshot_coverall_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-coverall",
        "coverall_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the in-repo `flat-enum` fixture. P3g adds
/// flat-enum rendering (`enum class`); this fixture is a deliberately
/// tiny `enum Animal { Dog, Cat }` plus a round-trip function, so it
/// isolates the flat-enum codegen path with zero other unsupported
/// types in the way. Error rendering is covered by
/// `snapshot_arithmetic_kotlin`, which now sees the `ArithmeticError →
/// ArithmeticException` typed-error output. The upstream
/// `uniffi-fixture-enum-types` intentionally *isn't* used here because
/// it includes sealed-variant enums + objects that would either panic
/// or render silently wrong until P3h+ lands the remaining types.
#[test]
fn snapshot_flat_enum_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-flat-enum",
        "flat_enum_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the `primitive-arrays` fixture. P3h adds
/// generic `Sequence<T>` codegen; the fixture exercises `Vec<i16>`,
/// `Vec<i32>`, `Vec<i64>`, `Vec<f32>`, `Vec<f64>`, `Vec<bool>`, and
/// the unsigned variants. On the Java side these route to primitive
/// arrays (`int[]`, `double[]`, ...); on the Kotlin side P3h uses the
/// generic `List<T>` path for all of them (boxed `List<Int>`,
/// `List<Double>`, ...). Unboxed primitive arrays (`IntArray`,
/// `DoubleArray`, ...) are tracked as P3h-primitive.
#[test]
fn snapshot_primitive_arrays_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-primitive-arrays",
        "primitive_arrays_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the in-repo `string-map` fixture. P3h
/// adds generic `Map<K, V>` codegen; this fixture is a deliberately
/// tiny `HashMap<String, i32>` round-trip, isolating the map codegen
/// path with zero other unsupported types. Coverall would exercise
/// maps too but also pulls in objects and callbacks that still panic
/// until P3i+.
#[test]
fn snapshot_string_map_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-string-map",
        "string_map_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the sprites fixture. P3i adds
/// `Type::Object` codegen — handle-based wrapper classes with
/// `AutoCloseable` + `UniffiCleaner` finalization, `callWithHandle`
/// routing for instance methods, and companion-object factories for
/// named constructors. Sprites is a clean isolation: records (Point,
/// Vector) + one interface (Sprite) with a primary constructor, a
/// named constructor (`new_relative_to`), and several methods —
/// nothing unsupported at P3i scope (no callbacks, no async, no
/// trait interfaces, no error-as-object).
#[test]
fn snapshot_sprites_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-example-sprites",
        "sprites_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the in-repo `simple-callback` fixture.
/// P3j-b adds `Type::CallbackInterface` codegen — foreign-implemented
/// traits with a vtable of upcall stubs. This fixture is deliberately
/// tiny: one pure `callback_interface` trait (`Greeter` with a single
/// sync, non-throwing method) plus a namespace function that takes an
/// instance and invokes it. No errors, no async, no
/// `[Trait, WithForeign]` — those land in later phases.
#[test]
fn snapshot_simple_callback_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-simple-callback",
        "simple_callback_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the in-repo `trait-with-foreign` fixture.
/// P3j-c adds `[Trait, WithForeign]` object codegen — trait objects
/// implementable in either Rust or Kotlin. Exercises:
///   * `interface Counter` user-facing trait declaration.
///   * `class CounterImpl(handle: Long) : Counter, AutoCloseable` —
///     Rust-side wrapper; methods rendered with `override`.
///   * `FfiConverterTypeCounter` with LSB-tagged lift/lower —
///     even handles return `CounterImpl`, odd handles route through
///     a per-FfiConverter `handleMap`.
///   * `UniffiCallbackInterfaceCounter.register()` invoked at
///     `UniffiLib.init` time so Rust knows how to dispatch back to a
///     Kotlin implementor.
#[test]
fn snapshot_trait_with_foreign_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-trait-with-foreign",
        "trait_with_foreign_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the upstream `custom-types` example. P3k
/// adds `Type::Custom` codegen — user-defined newtype wrappers
/// around a builtin (e.g. `custom Handle<Int64>` → `data class
/// Handle(val value: Long)`). The fixture exercises both branches:
///   * Auto-generated wrapper + delegating FfiConverter for `Handle`,
///     `TimeIntervalMs`, `TimeIntervalSecDbl`, `TimeIntervalSecFlt`
///     (no `uniffi.toml` config — defaults).
///   * Config-driven path for `Url` — user-supplied `java.net.URL`
///     type, `java.net.URI` / `java.net.URL` imports, and
///     `URI(builtinValue).toURL()` / `value.toString()` lift/lower
///     expressions interpolated into the FfiConverter.
#[test]
fn snapshot_custom_types_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-example-custom-types",
        "custom_types_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the upstream `enum-types` fixture. Now
/// reachable post-P3l-enums (non-flat enum support): the fixture
/// declares `Animal`/`AnimalUInt`/etc. (flat with explicit
/// discriminants — fall through P3g's flat path), `AnimalEnum` and
/// `AnimalNamedEnum` (associated-data enums with `Arc<AnimalObject>`
/// / `AnimalRecord` variants — exercise the new `sealed class` path),
/// `AnimalObject` (Rust-only `uniffi::Object` — exercises P3i), and
/// `AnimalRecord` (a record with one field — P3f). Validates that
/// non-flat variants reference object/record types via the same
/// `<Field>|read_fn` filter chain the flat path uses.
#[test]
fn snapshot_enum_types_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-enum-types",
        "enum_types_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the in-repo `error-data` fixture. P3l
/// adds associated-data error codegen — `[Error] interface MyError`
/// (or `#[derive(uniffi::Error)] pub enum` with field-bearing
/// variants) renders as a `sealed class` extending
/// `kotlin.Exception`, with per-variant nested classes carrying
/// fields. The fixture has two variants — one with a single
/// `String` field, one with `i32 + String` — so single- and
/// multi-field paths both surface in the snapshot. The
/// `try_call(network_failure)` namespace function returns a
/// `Result<String, ApiError>` so `@Throws(ApiError::class)` and the
/// error-handler routing both render alongside.
#[test]
fn snapshot_error_data_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-error-data",
        "error_data_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the in-repo `trait-methods-kt` fixture.
/// P3l-traits adds `#[uniffi::export(Display, Eq, Ord, Hash)]`
/// proc-macro trait method overrides on Records and Objects. The
/// fixture exercises one record (`TraitRec`) and one object
/// (`TraitObj`), both with all four trait exports — so the snapshot
/// covers every arm of the new `uniffi_trait_impls` macro across
/// both self-type dispatch paths (`lower(this)` for Records vs
/// `callWithHandle { uniffiHandle -> ... }` for Objects). Enums and
/// errors with trait exports are deferred to a follow-up.
#[test]
fn snapshot_trait_methods_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-trait-methods-kt",
        "trait_methods_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Kotlin-side snapshot for the upstream `futures` fixture. P3k-async
/// adds full async support — `suspend fun` codegen for namespace
/// functions and Object instance methods, routed through
/// `UniffiAsyncHelpers.uniffiRustCallAsync` via
/// `kotlinx.coroutines.suspendCancellableCoroutine` for the polling
/// continuation. The fixture exercises every async return shape:
/// `bool` / `i64` / `String` / `Option<T>` / `Vec<T>` /
/// `Record` / `Object`, plus async methods on Objects. Async
/// callback interface (`AsyncParser`) trait stubs still throw
/// `NotImplementedError` — that's deferred to P3k-async-b.
#[test]
fn snapshot_futures_kotlin() -> Result<()> {
    snapshot_fixture_for(
        "uniffi-fixture-futures",
        "futures_kotlin",
        Language::Kotlin,
        "kt",
    )
}

/// Snapshots for the upstream `uniffi-fixture-time` fixture, which
/// exercises both `Timestamp` (`SystemTime`) and `Duration` —
/// including pre-epoch instants (negative seconds), the only
/// in-test-suite coverage of the miscellany code path. Both
/// languages snapshot the same fixture so a divergence in the
/// Java/Kotlin Helper templates surfaces as a paired diff.
#[test]
fn snapshot_time() -> Result<()> {
    snapshot_fixture("uniffi-fixture-time", "time")
}

#[test]
fn snapshot_time_kotlin() -> Result<()> {
    snapshot_fixture_for("uniffi-fixture-time", "time_kotlin", Language::Kotlin, "kt")
}
