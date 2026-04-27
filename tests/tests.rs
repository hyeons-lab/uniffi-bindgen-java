/* This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
* file, You can obtain one at http://mozilla.org/MPL/2.0/. */

use anyhow::{Context, Result, bail};
use camino::{Utf8Path, Utf8PathBuf};
use cargo_metadata::{CrateType, MetadataCommand, Package, Target};
use std::io::{Read, Write};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};
use std::{env, fs};
use uniffi_bindgen::{BindgenLoader, BindgenPaths};
use uniffi_bindgen_java::{GenerateOptions, Language, generate};
use uniffi_testing::UniFFITestHelper;

/// Run the test fixtures from UniFFI
fn run_test(fixture_name: &str, test_file: &str) -> Result<()> {
    let test_path = Utf8Path::new(".").join("tests").join(test_file);
    let test_helper = UniFFITestHelper::new(fixture_name)?;
    let out_dir = test_helper.create_out_dir(env!("CARGO_TARGET_TMPDIR"), &test_path)?;
    let cdylib_path = test_helper.cdylib_path()?;

    let loader = bindgen_loader_with_config_override(fixture_name, &test_path, &out_dir)?;

    // generate the fixture bindings
    let options = GenerateOptions::new(cdylib_path.clone(), out_dir.clone());
    generate(&loader, &options)?;

    let native_lib_dir = prepare_native_lib_dir(&out_dir, &cdylib_path)?;

    // compile generated bindings and form jar
    let jar_file = build_jar(fixture_name, &out_dir)?;

    // compile test
    let status = Command::new("javac")
        .arg("-classpath")
        .arg(calc_classpath(vec![&out_dir, &jar_file]))
        // Our tests should not produce any warnings.
        .arg("-Werror")
        .arg(&test_path)
        .spawn()
        .context("Failed to spawn `javac` to compile Java test")?
        .wait()
        .context("Failed to wait for `javac` when compiling Java test")?;
    if !status.success() {
        anyhow::bail!("running `javac` failed when compiling the Java test")
    }

    // run resulting test
    let compiled_path = test_path.file_stem().unwrap();
    let run_status = Command::new("java")
        // allow for runtime assertions
        .arg("-ea")
        // Enable FFM native access
        .arg("--enable-native-access=ALL-UNNAMED")
        // Set native library path so System.loadLibrary can find the cdylib
        .arg(format!("-Djava.library.path={}", native_lib_dir))
        .arg("-classpath")
        .arg(calc_classpath(vec![
            &out_dir,
            &jar_file,
            &test_path.parent().unwrap().to_path_buf(),
        ]))
        .arg(compiled_path)
        .spawn()
        .context("Failed to spawn `java` to run Java test")?
        .wait()
        .context("Failed to wait for `java` when running Java test")?;
    if !run_status.success() {
        anyhow::bail!("Running the `java` test failed.")
    }

    Ok(())
}

/// Run a test using an absolute path library override instead of java.library.path.
/// This validates that the generated loadLibrary() code uses System.load() for absolute paths.
fn run_test_with_library_override(
    fixture_name: &str,
    test_file: &str,
    namespace: &str,
) -> Result<()> {
    let test_path = Utf8Path::new(".").join("tests").join(test_file);
    let test_helper = UniFFITestHelper::new(fixture_name)?;
    // Use a synthetic path for out_dir so it doesn't collide with run_test's out_dir
    // (create_out_dir is deterministic based on the path).
    let out_dir_key = Utf8Path::new(".")
        .join("tests")
        .join("library_override")
        .join(test_file);
    let out_dir = test_helper.create_out_dir(env!("CARGO_TARGET_TMPDIR"), &out_dir_key)?;
    let cdylib_path = test_helper.cdylib_path()?;

    let mut paths = BindgenPaths::default();
    paths.add_cargo_metadata_layer(false)?;
    let loader = BindgenLoader::new(paths);

    let options = GenerateOptions::new(cdylib_path.clone(), out_dir.clone());
    generate(&loader, &options)?;

    // Copy the cdylib to a known absolute path (no symlink needed since we pass the full path)
    let native_lib_dir = out_dir.join("native");
    fs::create_dir_all(&native_lib_dir)?;
    let cdylib_filename = cdylib_path.file_name().unwrap();
    let extension = cdylib_path.extension().unwrap();
    let lib_base_name = cdylib_filename
        .strip_prefix("lib")
        .unwrap_or(cdylib_filename)
        .split('-')
        .next()
        .unwrap_or(cdylib_filename);
    let canonical_lib_name = format!("lib{}.{}", lib_base_name, extension);
    let lib_absolute_path = native_lib_dir.join(&canonical_lib_name);
    fs::copy(&cdylib_path, &lib_absolute_path)?;

    let jar_file = build_jar(fixture_name, &out_dir)?;

    let status = Command::new("javac")
        .arg("-classpath")
        .arg(calc_classpath(vec![&out_dir, &jar_file]))
        .arg("-Werror")
        .arg(&test_path)
        .spawn()
        .context("Failed to spawn `javac` to compile Java test")?
        .wait()
        .context("Failed to wait for `javac` when compiling Java test")?;
    if !status.success() {
        anyhow::bail!("running `javac` failed when compiling the Java test")
    }

    // Run with library override set to an absolute path and NO java.library.path,
    // so this can only work if the generated code uses System.load() for absolute paths.
    let compiled_path = test_path.file_stem().unwrap();
    let run_status = Command::new("java")
        .arg("-ea")
        .arg("--enable-native-access=ALL-UNNAMED")
        .arg(format!(
            "-Duniffi.component.{}.libraryOverride={}",
            namespace, lib_absolute_path
        ))
        // Deliberately NOT setting -Djava.library.path
        .arg("-classpath")
        .arg(calc_classpath(vec![
            &out_dir,
            &jar_file,
            &test_path.parent().unwrap().to_path_buf(),
        ]))
        .arg(compiled_path)
        .spawn()
        .context("Failed to spawn `java` to run Java test")?
        .wait()
        .context("Failed to wait for `java` when running Java test")?;
    if !run_status.success() {
        anyhow::bail!("Running the `java` test with library override failed.")
    }

    Ok(())
}

/// Run a generated Kotlin test fixture: produce Kotlin bindings,
/// compile them with `kotlinc`, then compile and run the test
/// script in a JVM with FFM native access enabled. Mirrors
/// `run_test()` for the Kotlin backend.
///
/// Skips silently (returns `Ok(())` after an `eprintln!` notice)
/// when `kotlinc` isn't on `$PATH` and `KOTLINC` isn't set.
/// Tests calling this should be `#[ignore]`d so the default
/// `cargo test` invocation doesn't run them; opt in with
/// `cargo test -- --ignored`.
fn run_kotlin_test(fixture_name: &str, test_file: &str) -> Result<()> {
    let Some(kotlinc) = kotlinc_path() else {
        eprintln!(
            "skip: `kotlinc` not found (set KOTLINC or install kotlinc) — \
             skipping Kotlin test for {fixture_name}"
        );
        return Ok(());
    };

    let test_path = Utf8Path::new(".").join("tests").join(test_file);
    let test_helper = UniFFITestHelper::new(fixture_name)?;
    // Use a synthetic out_dir key so we don't collide with the Java
    // `run_test` cache for the same fixture.
    let out_dir_key = Utf8Path::new(".")
        .join("tests")
        .join("kotlin")
        .join(test_file);
    let out_dir = test_helper.create_out_dir(env!("CARGO_TARGET_TMPDIR"), &out_dir_key)?;
    let cdylib_path = test_helper.cdylib_path()?;

    let loader = bindgen_loader_with_config_override(fixture_name, &test_path, &out_dir)?;

    let mut options = GenerateOptions::new(cdylib_path.clone(), out_dir.clone());
    options.language = Language::Kotlin;
    generate(&loader, &options)?;

    let native_lib_dir = prepare_native_lib_dir(&out_dir, &cdylib_path)?;

    // Compile the generated `.kt` files into a single bindings JAR.
    // Build the classpath: always nothing (`-include-runtime` bakes
    // in kotlin-stdlib so the harness is independent of where kotlinc
    // keeps its bundled stdlib); for fixtures whose bindings emit the
    // Async runtime, also fetch + add `kotlinx-coroutines-core-jvm`.
    // Detecting via the generated `UniffiAsyncHelpers.kt` file (the
    // marker name in `Async.kt`'s `// UNIFFI:FILE` header) means we
    // don't pay the network round-trip for fixtures that don't need
    // it (e.g. arithmetic).
    let needs_coroutines = glob::glob(out_dir.join("**/UniffiAsyncHelpers.kt").as_str())?
        .next()
        .is_some();
    let coroutines_jar = if needs_coroutines {
        Some(ensure_kotlinx_coroutines_jar()?)
    } else {
        None
    };
    let coroutines_paths: Vec<&Utf8PathBuf> = coroutines_jar.iter().collect();

    let bindings_jar = out_dir.join(format!("{}-kt.jar", fixture_name));
    let kt_files: Vec<String> = glob::glob(out_dir.join("**/*.kt").as_str())?
        .flatten()
        .map(|p| p.to_string_lossy().into_owned())
        .collect();
    if kt_files.is_empty() {
        bail!("no generated .kt files under {}", out_dir);
    }
    let mut kotlinc_cmd = Command::new(kotlinc.as_std_path());
    kotlinc_cmd.arg("-include-runtime");
    if needs_coroutines {
        kotlinc_cmd
            .arg("-classpath")
            .arg(calc_classpath(coroutines_paths.clone()));
    }
    let kotlinc_status = kotlinc_cmd
        .arg("-d")
        .arg(bindings_jar.as_str())
        .args(&kt_files)
        .spawn()
        .with_context(|| format!("spawning kotlinc at {kotlinc}"))?
        .wait()
        .context("waiting for kotlinc on bindings")?;
    if !kotlinc_status.success() {
        bail!(
            "kotlinc failed compiling generated bindings under {}",
            out_dir
        );
    }

    // Compile the test script against the bindings JAR. kotlinc auto-
    // includes its own stdlib at compile time; coroutines goes on the
    // classpath only when the bindings need it (so test programs that
    // use `runBlocking { ... }` / `suspend fun` resolve when
    // applicable).
    let test_classes_dir = out_dir.join("test-classes");
    fs::create_dir_all(&test_classes_dir)?;
    let mut test_compile_classpath: Vec<&Utf8PathBuf> = vec![&bindings_jar];
    test_compile_classpath.extend(coroutines_paths.iter().copied());
    let test_kotlinc_status = Command::new(kotlinc.as_std_path())
        .arg("-classpath")
        .arg(calc_classpath(test_compile_classpath))
        .arg("-d")
        .arg(test_classes_dir.as_str())
        .arg(test_path.as_str())
        .spawn()
        .context("spawning kotlinc on test script")?
        .wait()
        .context("waiting for kotlinc on test script")?;
    if !test_kotlinc_status.success() {
        bail!("kotlinc failed compiling Kotlin test {}", test_path);
    }

    // Run with FFM native-access flags + java.library.path. Top-level
    // `fun main()` in `Foo.kt` lands as class `FooKt` after kotlinc.
    // The bindings JAR contains kotlin-stdlib classes (via
    // `-include-runtime`); coroutines lands on the runtime classpath
    // only when the fixture's Async runtime is generated.
    let main_class = format!("{}Kt", test_path.file_stem().unwrap());
    let mut runtime_classpath: Vec<&Utf8PathBuf> = vec![&bindings_jar, &test_classes_dir];
    runtime_classpath.extend(coroutines_paths.iter().copied());
    let run_classpath = calc_classpath(runtime_classpath);
    let run_status = Command::new("java")
        .arg("-ea")
        .arg("--enable-native-access=ALL-UNNAMED")
        .arg(format!("-Djava.library.path={}", native_lib_dir))
        .arg("-classpath")
        .arg(run_classpath)
        .arg(&main_class)
        .spawn()
        .context("spawning java to run Kotlin test")?
        .wait()
        .context("waiting for java to run Kotlin test")?;
    if !run_status.success() {
        bail!("Kotlin test {main_class} failed at runtime");
    }

    Ok(())
}

/// Pinned Maven coordinate + SHA-256 for `kotlinx-coroutines-core-jvm`.
/// Bumping the version requires updating both constants together;
/// `KOTLINX_COROUTINES_SHA256` is verified against the downloaded jar
/// so a stale-version checkout won't silently use a mismatched runtime.
const KOTLINX_COROUTINES_VERSION: &str = "1.10.2";
const KOTLINX_COROUTINES_SHA256: &str =
    "5ca175b38df331fd64155b35cd8cae1251fa9ee369709b36d42e0a288ccce3fd";

/// Download `kotlinx-coroutines-core-jvm-<VERSION>.jar` from Maven
/// Central, SHA-256 verify, and cache under `target/kotlin-deps/`.
/// Returns the cached path. On a cache hit, only re-verifies the
/// hash; on miss (or hash mismatch) re-downloads.
///
/// Why download instead of vendor? A 1.4 MB binary blob in-tree
/// drags every clone; downloading once per machine costs the same
/// total bytes and keeps the version pin visible in source.
///
/// Shells out to `curl` for the download and `shasum`/`sha256sum`
/// for verification — no Rust crypto crate dep introduced.
///
/// Concurrency: parallel test threads can both reach the cache-miss
/// branch at once. Downloading directly to the canonical cache path
/// would let two `curl` invocations interleave bytes and produce a
/// corrupted jar. Instead we download to a per-call unique temp
/// file and `rename` into place — `rename` is atomic on POSIX so
/// readers always observe either a complete or absent jar.
fn ensure_kotlinx_coroutines_jar() -> Result<Utf8PathBuf> {
    // CARGO_TARGET_TMPDIR is `<workspace>/target/tmp/`; sibling
    // kotlin-deps/ survives across `cargo test` runs but blows away
    // on `cargo clean`, which is the right cache lifetime here.
    let tmp = Utf8Path::new(env!("CARGO_TARGET_TMPDIR"));
    let cache_root = tmp
        .parent()
        .with_context(|| format!("CARGO_TARGET_TMPDIR has no parent: {tmp}"))?
        .join("kotlin-deps");
    fs::create_dir_all(&cache_root)?;

    let jar_name = format!("kotlinx-coroutines-core-jvm-{KOTLINX_COROUTINES_VERSION}.jar");
    let cached = cache_root.join(&jar_name);

    if cached.is_file() && verify_sha256(&cached, KOTLINX_COROUTINES_SHA256)? {
        return Ok(cached);
    }

    // pid + nanos disambiguates parallel cargo-test threads (same
    // process) and parallel cargo invocations (different processes).
    // Worst case both finish, both rename to `cached` — last writer
    // wins, the file is still a valid jar (both downloads have the
    // same bytes since the URL is fixed). No corruption possible.
    let pid = std::process::id();
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("Time went backwards")
        .as_nanos();
    let temp = cache_root.join(format!("{jar_name}.{pid}.{nanos}.tmp"));

    let url = format!(
        "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/\
         kotlinx-coroutines-core-jvm/{KOTLINX_COROUTINES_VERSION}/{jar_name}"
    );
    let status = Command::new("curl")
        .arg("-fsSL")
        .arg("--output")
        .arg(temp.as_str())
        .arg(&url)
        .spawn()
        .with_context(|| format!("spawning curl to fetch {url}"))?
        .wait()
        .context("waiting for curl on coroutines jar")?;
    if !status.success() {
        let _ = fs::remove_file(&temp);
        bail!("curl failed to download {url}");
    }

    if !verify_sha256(&temp, KOTLINX_COROUTINES_SHA256)? {
        let _ = fs::remove_file(&temp);
        bail!(
            "downloaded {jar_name} did not match expected SHA-256 \
             ({KOTLINX_COROUTINES_SHA256})"
        );
    }
    fs::rename(&temp, &cached)
        .with_context(|| format!("atomically renaming {temp} to {cached}"))?;
    Ok(cached)
}

/// Verify that `path`'s SHA-256 matches `expected_hex` (lowercase
/// hex). Tries `shasum -a 256` first (macOS default), falls back
/// to `sha256sum` (most Linux distros). Returns `Ok(false)` on
/// hash mismatch so callers can decide whether to re-download.
fn verify_sha256(path: &Utf8Path, expected_hex: &str) -> Result<bool> {
    let output = Command::new("shasum")
        .arg("-a")
        .arg("256")
        .arg(path.as_str())
        .output()
        .or_else(|_| {
            Command::new("sha256sum")
                .arg(path.as_str())
                .output()
                .context("neither shasum nor sha256sum available on PATH")
        })?;
    if !output.status.success() {
        bail!("hash command failed for {path}");
    }
    let stdout = String::from_utf8(output.stdout).context("hash command stdout not UTF-8")?;
    let actual = stdout
        .split_whitespace()
        .next()
        .with_context(|| format!("empty hash output for {path}"))?;
    Ok(actual.eq_ignore_ascii_case(expected_hex))
}

/// Locate the `kotlinc` binary. Honors `KOTLINC` env var first
/// (CI override), then falls back to `which kotlinc`. Returns
/// `None` when neither resolves so callers can skip gracefully.
fn kotlinc_path() -> Option<Utf8PathBuf> {
    if let Ok(p) = env::var("KOTLINC") {
        let p = Utf8PathBuf::from(p);
        if p.is_file() {
            return Some(p);
        }
    }
    let output = Command::new("which").arg("kotlinc").output().ok()?;
    if !output.status.success() {
        return None;
    }
    let trimmed = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if trimmed.is_empty() {
        None
    } else {
        Some(Utf8PathBuf::from(trimmed))
    }
}

/// Build a `BindgenLoader` that consults the fixture's `uniffi.toml`
/// (if any) merged with a sibling `uniffi-extras.toml` (if any), and
/// also adds the cargo-metadata layer for fixture discovery. Same
/// override semantics that `run_test` originally inlined; factored
/// out so `run_kotlin_test` can use it too — without this, Kotlin
/// runtime tests would silently generate different bindings for any
/// fixture that ships TOML config (e.g. `TestCustomTypes`).
fn bindgen_loader_with_config_override(
    fixture_name: &str,
    test_path: &Utf8Path,
    out_dir: &Utf8Path,
) -> Result<BindgenLoader> {
    let maybe_base = find_uniffi_toml(fixture_name)?.and_then(read_file_contents);
    let maybe_extras = read_file_contents(test_path.with_file_name("uniffi-extras.toml"));

    // Structural merge: parse base + extras as TOML tables, recursively
    // merge with extras winning, then serialize. String concatenation
    // doesn't work because both files can declare the same `[bindings.<lang>]`
    // section header (e.g. fixture pins `package_name`, extras adds
    // `omit_checksums`) — TOML rejects duplicate-key headers.
    let merged = merge_toml_overrides(maybe_base.as_deref(), maybe_extras.as_deref())?;

    let mut paths = BindgenPaths::default();
    if !merged.is_empty() {
        // Unique-ish per-fixture override file; nanosecond is enough
        // to avoid collisions across parallel fixtures.
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("Time went backwards")
            .as_nanos();
        let override_path = out_dir.with_file_name(format!("{fixture_name}-{now}.toml"));
        write_file_contents(&override_path, &merged)?;
        paths.add_config_override_layer(override_path);
    }
    paths.add_cargo_metadata_layer(false)?;
    Ok(BindgenLoader::new(paths))
}

/// Merge two TOML override sources into a single serialized document.
/// Recursively merges tables; on key collision, `extras` wins. Empty
/// inputs are treated as None. Empty result means "no override."
fn merge_toml_overrides(base: Option<&str>, extras: Option<&str>) -> Result<String> {
    fn merge_tables(base: &mut toml::Table, overlay: toml::Table) {
        for (k, v) in overlay {
            if let (Some(toml::Value::Table(b)), toml::Value::Table(o)) = (base.get_mut(&k), &v) {
                merge_tables(b, o.clone());
            } else {
                base.insert(k, v);
            }
        }
    }

    let parse = |s: Option<&str>| -> Result<Option<toml::Table>> {
        match s {
            Some(s) if !s.trim().is_empty() => Ok(Some(toml::from_str(s).context("parse toml")?)),
            _ => Ok(None),
        }
    };

    Ok(match (parse(base)?, parse(extras)?) {
        (Some(mut b), Some(e)) => {
            merge_tables(&mut b, e);
            toml::to_string(&b).context("serialize merged toml")?
        }
        (Some(t), None) | (None, Some(t)) => toml::to_string(&t).context("serialize toml")?,
        (None, None) => String::new(),
    })
}

/// Copy the cdylib into `out_dir/native/` and create the
/// `lib<name>.<ext>` symlink that `System.loadLibrary` expects.
/// Shared by `run_test` (Java) and `run_kotlin_test` (Kotlin) so
/// there's one canonical implementation of the symlink dance.
fn prepare_native_lib_dir(out_dir: &Utf8Path, cdylib_path: &Utf8Path) -> Result<Utf8PathBuf> {
    let native_lib_dir = out_dir.join("native");
    fs::create_dir_all(&native_lib_dir)?;
    let cdylib_filename = cdylib_path.file_name().unwrap();
    let cdylib_dest = native_lib_dir.join(cdylib_filename);
    fs::copy(cdylib_path, &cdylib_dest)?;

    let extension = cdylib_path.extension().unwrap();
    let lib_base_name = cdylib_filename
        .strip_prefix("lib")
        .unwrap_or(cdylib_filename)
        .split('-')
        .next()
        .unwrap_or(cdylib_filename);
    let expected_lib_name = format!("lib{}.{}", lib_base_name, extension);
    let symlink_path = native_lib_dir.join(&expected_lib_name);
    if !symlink_path.exists() {
        std::os::unix::fs::symlink(cdylib_dest.file_name().unwrap(), &symlink_path)?;
    }
    Ok(native_lib_dir)
}

/// Get the uniffi_toml of the fixture if it exists.
/// It looks for it in the root directory of the project `name`.
fn find_uniffi_toml(name: &str) -> Result<Option<Utf8PathBuf>> {
    let metadata = MetadataCommand::new()
        .exec()
        .expect("error running cargo metadata");
    let matching: Vec<&Package> = metadata
        .packages
        .iter()
        .filter(|p| p.name == name)
        .collect();
    let package = match matching.len() {
        1 => matching[0].clone(),
        n => bail!("cargo metadata return {n} packages named {name}"),
    };
    let cdylib_targets: Vec<&Target> = package
        .targets
        .iter()
        .filter(|t| t.crate_types.iter().any(|t| t == &CrateType::CDyLib))
        .collect();
    let target = match cdylib_targets.len() {
        1 => cdylib_targets[0],
        n => bail!("Found {n} cdylib targets for {}", package.name),
    };
    let maybe_uniffi_toml = target
        .src_path
        .parent()
        .map(|uniffi_toml_dir| uniffi_toml_dir.with_file_name("uniffi.toml"));
    Ok(maybe_uniffi_toml)
}

/// Generate java bindings for the given namespace, then use the Java
/// command-line tools to compile them into a .jar file.
fn build_jar(fixture_name: &str, out_dir: &Utf8PathBuf) -> Result<Utf8PathBuf> {
    let mut jar_file = Utf8PathBuf::from(out_dir);
    jar_file.push(format!("{}.jar", fixture_name));
    let staging_dir = out_dir.join("staging");

    let status = Command::new("javac")
        // Our generated bindings should not produce any warnings; fail tests if they do.
        .arg("-Werror")
        .arg("-d")
        .arg(&staging_dir)
        .arg("-classpath")
        .arg(calc_classpath(vec![]))
        .args(
            glob::glob(&out_dir.join("**/*.java").into_string())?
                .flatten()
                .map(|p| String::from(p.to_string_lossy())),
        )
        .spawn()
        .context("Failed to spawn `javac` to compile the bindings")?
        .wait()
        .context("Failed to wait for `javac` when compiling the bindings")?;
    if !status.success() {
        bail!("running `javac` failed when compiling the bindings")
    }

    let jar_status = Command::new("jar")
        .current_dir(out_dir)
        .arg("cf")
        .arg(jar_file.file_name().unwrap())
        .arg("-C")
        .arg(&staging_dir)
        .arg(".")
        .spawn()
        .context("Failed to spawn `jar` to package the bindings")?
        .wait()
        .context("Failed to wait for `jar` when packaging the bindings")?;
    if !jar_status.success() {
        bail!("running `jar` failed")
    }

    Ok(jar_file)
}

fn calc_classpath(extra_paths: Vec<&Utf8PathBuf>) -> String {
    extra_paths
        .into_iter()
        .map(|p| p.to_string())
        // Add the system classpath as a component, using the fact that env::var returns an Option,
        // which implement Iterator
        .chain(env::var("CLASSPATH"))
        .collect::<Vec<String>>()
        .join(":")
}

/// Read the contents of the file. Any errors will be turned into None.
fn read_file_contents(path: Utf8PathBuf) -> Option<String> {
    if let Ok(metadata) = fs::metadata(&path) {
        if metadata.is_file() {
            let mut content = String::new();
            std::fs::File::open(path)
                .ok()?
                .read_to_string(&mut content)
                .ok()?;
            Some(content)
        } else {
            None
        }
    } else {
        None
    }
}

fn write_file_contents(path: &Utf8PathBuf, contents: &str) -> Result<()> {
    std::fs::File::create(path)?.write_all(contents.as_bytes())?;
    Ok(())
}

macro_rules! fixture_tests {
    {
        $(($test_name:ident, $fixture_name:expr, $test_script:expr),)*
    } => {
    $(
        #[test]
        fn $test_name() -> Result<()> {
            run_test($fixture_name, $test_script)
        }
    )*
    }
}

fixture_tests! {
    (test_arithmetic, "uniffi-example-arithmetic", "scripts/TestArithmetic.java"),
    (test_geometry, "uniffi-example-geometry", "scripts/TestGeometry.java"),
    (test_rondpoint, "uniffi-example-rondpoint", "scripts/TestRondpoint.java"),
    // todolist: namespace class `Todolist` and object class `TodoList` produce filenames that
    // collide on case-insensitive filesystems (macOS).
    // (test_todolist, "uniffi-example-todolist", "scripts/TestTodolist.java"),
    (test_sprites, "uniffi-example-sprites", "scripts/TestSprites.java"),
    (test_coverall, "uniffi-fixture-coverall", "scripts/TestFixtureCoverall.java"),
    (test_chronological, "uniffi-fixture-time", "scripts/TestChronological.java"),
    (test_custom_types, "uniffi-example-custom-types", "scripts/TestCustomTypes/TestCustomTypes.java"),
    (test_external_types, "uniffi-fixture-ext-types", "scripts/TestImportedTypes/TestImportedTypes.java"),
    (test_futures, "uniffi-example-futures", "scripts/TestFutures.java"),
    (test_futures_fixtures, "uniffi-fixture-futures", "scripts/TestFixtureFutures/TestFixtureFutures.java"),
    (test_trait_methods, "uniffi-fixture-trait-methods", "scripts/TestTraitMethods.java"),
    (test_omit_checksums, "uniffi-example-arithmetic", "scripts/TestOmitChecksums/TestOmitChecksums.java"),
    (test_proc_macro, "uniffi-fixture-proc-macro", "scripts/TestProcMacro.java"),
    (test_rename, "uniffi-fixture-rename", "scripts/TestRename/TestRename.java"),
    (test_primitive_arrays, "uniffi-fixture-primitive-arrays", "scripts/TestPrimitiveArrays.java"),
}

#[test]
fn test_library_override_absolute_path() -> Result<()> {
    run_test_with_library_override(
        "uniffi-example-arithmetic",
        "scripts/TestArithmetic.java",
        "arithmetic",
    )
}

/// Kotlin smoke test: generate Kotlin bindings for the upstream
/// `arithmetic` example, compile with `kotlinc`, run the result
/// in a JVM with FFM native access enabled. `#[ignore]` keeps
/// this off the default `cargo test` cycle so devs without
/// `kotlinc` aren't blocked; opt in with `cargo test -- --ignored`.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_arithmetic_kotlin() -> Result<()> {
    run_kotlin_test("uniffi-example-arithmetic", "scripts/TestArithmetic.kt")
}

/// Kotlin runtime test for the upstream `coverall` fixture. PR 3a
/// of the coverall round-trip arc: covers the strategic subset
/// (records with all scalar types + optionals, Coveralls
/// constructor / getName / strongCount, a single typed-error
/// throw). Traits, complex errors, and async land in PRs 3b / 3c.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_coverall_kotlin() -> Result<()> {
    run_kotlin_test("uniffi-fixture-coverall", "scripts/TestFixtureCoverall.kt")
}

/// Kotlin runtime test for `omit_checksums = true`. Reuses the
/// upstream `arithmetic` fixture but with the sibling
/// `tests/scripts/TestOmitChecksums/uniffi-extras.toml` flipping the
/// flag for both Java and Kotlin. If the codegen gating works, the
/// generated Kotlin bindings have neither `uniffiCheckApiChecksums()`
/// nor an init-time call; the fact that they still load and basic
/// operations work is the regression signal.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_omit_checksums_kotlin() -> Result<()> {
    run_kotlin_test(
        "uniffi-example-arithmetic",
        "scripts/TestOmitChecksums/TestOmitChecksums.kt",
    )
}

/// Kotlin runtime test for the upstream `geometry` example: data
/// classes (records) round-tripped through namespace functions,
/// including a nullable-Point return path.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_geometry_kotlin() -> Result<()> {
    run_kotlin_test("uniffi-example-geometry", "scripts/TestGeometry.kt")
}

/// Kotlin runtime test for the upstream `sprites` example: object
/// wrapper with primary constructor (nullable `Point?`), companion-
/// object `newRelativeTo` factory, `AutoCloseable` via `.use {}`,
/// post-close `IllegalStateException`, and a namespace function.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_sprites_kotlin() -> Result<()> {
    run_kotlin_test("uniffi-example-sprites", "scripts/TestSprites.kt")
}

/// Kotlin runtime test for the local `primitive-arrays` fixture:
/// every primitive array type (`FloatArray`, `DoubleArray`,
/// `ShortArray`, `IntArray`, `LongArray`, `BooleanArray`),
/// signed + unsigned variants, plus empty + large (10k element)
/// arrays.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_primitive_arrays_kotlin() -> Result<()> {
    run_kotlin_test(
        "uniffi-fixture-primitive-arrays",
        "scripts/TestPrimitiveArrays.kt",
    )
}

/// Kotlin runtime test for the upstream `chronological` fixture:
/// `java.time.Instant` / `Duration` round-trip through the
/// Timestamp / Duration codegen ported in PR #34. Includes
/// pre-epoch timestamps, optional / nullable arguments, and
/// overflow surfaces as `DateTimeException` from the JVM.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_chronological_kotlin() -> Result<()> {
    run_kotlin_test("uniffi-fixture-time", "scripts/TestChronological.kt")
}

/// Kotlin runtime test for the local `trait-methods-kt` fixture:
/// `#[uniffi::export(Display, Eq, Ord, Hash)]` on records, objects,
/// flat enums, errors, and non-flat enums. Each override is routed
/// through Rust via FFI, so the assertions verify both
/// `toString` / `equals` / `hashCode` / `compareTo` plumbing and
/// the Rust-side custom impls.
#[test]
#[ignore = "requires kotlinc; opt in with `cargo test -- --ignored`"]
fn test_trait_methods_kotlin() -> Result<()> {
    run_kotlin_test(
        "uniffi-fixture-trait-methods-kt",
        "scripts/TestTraitMethods.kt",
    )
}
