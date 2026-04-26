# uniffi-bindgen-java

Generate [UniFFI](https://github.com/mozilla/uniffi-rs) bindings for Java and Kotlin.

The Java backend uses Java-native types where possible — `CompletableFuture` for async, JSpecify nullness annotations for null-safety, no external runtime dependencies. The Kotlin backend uses Kotlin idioms — `suspend fun` and `kotlinx.coroutines` for async, native nullable types (`T?`), `data class` records, `sealed class` errors, `companion object` factories. Both share the same FFI runtime; pick whichever fits the consumer.

The official upstream Kotlin bindings shipped by `uniffi-rs` are also usable from any JVM language including Java; this project's Kotlin backend differs in two main ways: it generates against Java's [Foreign Function & Memory API](https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html) (Project Panama) rather than JNA — see [benches/](benches/) for the performance gap — and it stays in lockstep with the Java backend's wire format so the two outputs are layout-compatible.

We highly recommend you use [UniFFI's proc-macro definition](https://mozilla.github.io/uniffi-rs/latest/proc_macro/index.html) instead of UDL where possible.

## Requirements

* Java 22+: `javac`, and `jar` (for the Java backend); `kotlinc` 2.x and `kotlin-stdlib` (for the Kotlin backend).
* At runtime, the JVM must be allowed to use the Foreign Function & Memory API. For classpath-based applications, pass `--enable-native-access=ALL-UNNAMED` to `java`. For JPMS modules, use `--enable-native-access=your.module.name`. See [Java's documentation](https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html#GUID-080FE2FA-F96A-4987-B4E1-A9F089D11B54__GUID-70A202F4-46C0-4D4D-8CD0-9D147854F776) for more information.
* Kotlin consumers using async (`suspend fun`) APIs need `org.jetbrains.kotlinx:kotlinx-coroutines-core` on their classpath. Kotlin consumers without async functions don't need it.

## Installation

MSRV is `1.87.0`.

`cargo install uniffi-bindgen-java --git https://github.com/IronCoreLabs/uniffi-bindgen-java`

## Usage

```
uniffi-bindgen-java --help
Java and Kotlin scaffolding and bindings generator for Rust

Usage:

Commands:
  generate     Generate bindings (Java by default; pass `--language kotlin` for Kotlin)
  scaffolding  Generate Rust scaffolding code
  print-repr   Print a debug representation of the interface from a dynamic library

Options:
  -h, --help     Print help
  -V, --version  Print version
```

### Generate Bindings

By default, `generate` produces Java bindings. Pass `--language kotlin` to produce Kotlin bindings instead — same scaffolding, different surface idioms.

```
uniffi-bindgen-java generate --help
Generate bindings (Java by default; pass `--language kotlin` for Kotlin)

Usage:

Arguments:
  <SOURCE>  Path to the UDL file or compiled library (.so, .dll, .dylib, or .a)

Options:
      --language <LANGUAGE>   Target output language. Defaults to Java [default: java] [possible values: java, kotlin]
  -o, --out-dir <OUT_DIR>     Directory in which to write generated files. Default is same folder as .udl file
  -n, --no-format             Do not try to format the generated bindings
  -c, --config <CONFIG>       Path to optional uniffi config file. This config is merged with the `uniffi.toml` config present in each crate, with its values taking precedence
      --crate <CRATE_NAME>    When a library is passed as SOURCE, only generate bindings for this crate. When a UDL file is passed, use this as the crate name instead of attempting to locate and parse Cargo.toml
      --metadata-no-deps      Whether we should exclude dependencies when running "cargo metadata". This will mean external types may not be resolved if they are implemented in crates outside of this workspace. This can be used in environments when all types are in the namespace and fetching all sub-dependencies causes obscure platform specific problems
  -h, --help                  Print help
  -V, --version               Print version
```

#### Java example

```
> git clone https://github.com/mozilla/uniffi-rs.git
> cd uniffi-rs/examples/arithmetic-proc-macro
> cargo b --release
> uniffi-bindgen-java generate --out-dir ./generated-java ../../target/release/libarithmeticpm.so
> ll generated-java/uniffi/arithmeticpm/
total 216
-rw-r--r-- 1 user users  295 Jul 24 13:02 ArithmeticExceptionErrorHandler.java
-rw-r--r-- 1 user users  731 Jul 24 13:02 ArithmeticException.java
-rw-r--r-- 1 user users 3126 Jul 24 13:02 Arithmeticpm.java
-rw-r--r-- 1 user users  512 Jul 24 13:02 AutoCloseableHelper.java
-rw-r--r-- 1 user users  584 Jul 24 13:02 FfiConverterBoolean.java
...
> cat generated-java/uniffi/arithmeticpm/Arithmeticpm.java
package uniffi.arithmeticpm;


import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
public class Arithmeticpm {
  public static long add(long a, long b) throws ArithmeticException {
            try {
...

```

#### Kotlin example

Pass `--language kotlin` to get a parallel Kotlin tree:

```
> uniffi-bindgen-java generate --language kotlin --out-dir ./generated-kt ../../target/release/libarithmeticpm.so
> ll generated-kt/uniffi/arithmeticpm/
-rw-r--r-- 1 user users  295 Jul 24 13:02 ArithmeticExceptionErrorHandler.kt
-rw-r--r-- 1 user users  731 Jul 24 13:02 ArithmeticException.kt
-rw-r--r-- 1 user users 3126 Jul 24 13:02 Arithmeticpm.kt
-rw-r--r-- 1 user users  584 Jul 24 13:02 FfiConverterBoolean.kt
...
> cat generated-kt/uniffi/arithmeticpm/Arithmeticpm.kt
package uniffi.arithmeticpm

object Arithmeticpm {
    @Throws(ArithmeticException::class)
    fun add(a: Long, b: Long): Long =
        UniffiHelpers.uniffiRustCallWithErrorLong(ArithmeticExceptionErrorHandler()) { _, _status ->
            UniffiLib.uniffi_arithmeticpm_fn_func_add(a, b, _status)
        }
...
```

The Kotlin output uses `object` for the namespace (so call sites read `Arithmeticpm.add(2, 4)` from Kotlin or `Arithmeticpm.INSTANCE.add(2, 4)` from Java), `data class` for records, `sealed class` for typed errors, and `suspend fun` (via `kotlinx.coroutines`) for async.

### Generate Scaffolding

```
uniffi-bindgen-java scaffolding --help
Generate Rust scaffolding code

Usage:

Arguments:
  <UDL_FILE>  Path to the UDL file

Options:
  -o, --out-dir <OUT_DIR>  Directory in which to write generated files. Default is same folder as .udl file
  -n, --no-format          Do not try to format the generated bindings
```

### Print Debug Representation

```
uniffi-bindgen-java print-repr --help
Print a debug representation of the interface from a dynamic library

Usage:

Arguments:
  <PATH>  Path to the library file (.so, .dll, .dylib, or .a)
```

## Integrating Bindings

After generation you'll have an `--out-dir` full of `.java` (or `.kt`, with `--language kotlin`) files. Package those into a `.jar` using your build tools of choice, and the result can be imported and used as per normal in any JVM project. The generated code uses the Foreign Function & Memory API (no external dependencies like JNA are required).

For the Java backend, top-level functions in the Rust library become static methods on a class named after the crate (`Arithmeticpm.add(...)`). For the Kotlin backend they become methods on an `object` of the same name (same call site syntax from Kotlin: `Arithmeticpm.add(...)`; from Java: `Arithmeticpm.INSTANCE.add(...)`). If your Kotlin code uses any `suspend fun` APIs, add `org.jetbrains.kotlinx:kotlinx-coroutines-core` as a runtime dependency.

## Configuration

The generated bindings can be configured via a `uniffi.toml` file. The Java backend reads `[bindings.java]`; the Kotlin backend reads `[bindings.kotlin]`. The two tables share most options but differ where the language idioms differ (e.g. Java has `nullness_annotations`; Kotlin doesn't need them because nullable/non-null is part of the language).

### Java options (`[bindings.java]`)

| Configuration name | Default | Description |
| --- | --- | --- |
| `package_name` | `uniffi.{namespace}` | The Java package name — the value used in the `package` statement at the top of generated files. |
| `cdylib_name` | `uniffi_{namespace}` | The name of the compiled Rust library containing the FFI implementation (not needed when using `generate --library`) |
| `generate_immutable_records` | `false` | Whether to generate records with immutable fields (`record` instead of `class`). |
| `custom_types` | | A map which controls how custom types are exposed to Java. See the [custom types section of the UniFFI manual](https://mozilla.github.io/uniffi-rs/latest/udl/custom_types.html#custom-types-in-the-bindings-code) |
| `external_packages` | | A map of packages to be used for the specified external crates. The key is the Rust crate name, the value is the Java package which will be used referring to types in that crate. See the [external types section of the manual](https://mozilla.github.io/uniffi-rs/latest/udl/ext_types_external.html#kotlin) |
| `rename` | | A map to rename types, functions, methods, and their members in the generated Java bindings. See the [renaming section](https://mozilla.github.io/uniffi-rs/latest/renaming.html). |
| `nullness_annotations` | `false` | Generate [JSpecify](https://jspecify.dev/) nullness annotations. Rust `Option<T>` maps to `@Nullable T`; all other types are non-null by default via `@NullMarked`. Requires `org.jspecify:jspecify` on the compile classpath. See [Nullness Annotations](#nullness-annotations). |
| `android` | `false` | Generate [PanamaPort](https://github.com/vova7878/PanamaPort)-compatible code for Android. Replaces `java.lang.foreign.*` with `com.v7878.foreign.*` and `java.lang.invoke.VarHandle` with `com.v7878.invoke.VarHandle`. Requires PanamaPort `io.github.vova7878.panama:Core` as a runtime dependency and Android API 26+. |
| `omit_checksums` | `false` | Whether to omit checking the library checksums as the library is initialized. Changing this will shoot yourself in the foot if you mixup your build pipeline in any way, but might speed up initialization. |

### Kotlin options (`[bindings.kotlin]`)

| Configuration name | Default | Description |
| --- | --- | --- |
| `package_name` | `uniffi.{namespace}` | The Kotlin package name — the value used in the `package` statement at the top of generated files. |
| `cdylib_name` | `uniffi_{namespace}` | The name of the compiled Rust library containing the FFI implementation (not needed when using `generate --library`). |
| `custom_types` | | A map which controls how custom types are exposed to Kotlin. Same shape as the Java table; see the [custom types section of the UniFFI manual](https://mozilla.github.io/uniffi-rs/latest/udl/custom_types.html#custom-types-in-the-bindings-code). |
| `external_packages` | | A map of Kotlin packages for external crates' types. Same shape as the Java table. |
| `rename` | | A map to rename types, functions, methods, and their members in the generated Kotlin bindings. Same shape as the Java table; see the [renaming section](https://mozilla.github.io/uniffi-rs/latest/renaming.html). |
| `android` | `false` | Generate [PanamaPort](https://github.com/vova7878/PanamaPort)-compatible code for Android. Replaces `java.lang.foreign.*` with `com.v7878.foreign.*` and `java.lang.invoke.VarHandle` with `com.v7878.invoke.VarHandle`. Requires PanamaPort `io.github.vova7878.panama:Core` as a runtime dependency and Android API 26+. |
| `omit_checksums` | `false` | Whether to omit checking the library checksums as the library is initialized. Changing this will shoot yourself in the foot if you mixup your build pipeline in any way, but might speed up initialization. |

The Kotlin backend has no `nullness_annotations` flag — Kotlin's null-safety is part of the language, so nullable types map directly to `T?` and non-null types stay bare. There's also no `generate_immutable_records` flag — Kotlin `data class` records are immutable by construction.

### Example

#### Custom types

```
[bindings.java]
package_name = "customtypes"

[bindings.java.custom_types.Url]
# Name of the type in the Java code
type_name = "URL"
# Classes that need to be imported
imports = ["java.net.URI", "java.net.URL"]
# Functions to convert between strings and URLs
lift = "new URI({}).toURL()"
lower = "{}.toString()"
```

#### External Types

```
[bindings.java.external_packages]
# This specifies that external types from the crate `rust-crate-name` will be referred by by the package `"java.package.name`.
rust-crate-name = "java.package.name"
```

## Library Loading

This applies to both backends — the loader is part of the shared FFI runtime, not the language-specific surface. The generated code uses `System.loadLibrary()` to find the native library via `java.library.path` by default. If your native library lives outside the standard search paths or automatic discovery doesn't work for your environment, you can specify an absolute path at runtime using a system property:

```
java -Duniffi.component.<namespace>.libraryOverride=/path/to/libmylib.so ...
```

Where `<namespace>` is the UniFFI namespace of your component (e.g., `arithmetic`). When the override is an absolute path, the generated code uses `System.load()` instead of `System.loadLibrary()`, bypassing `java.library.path` entirely.

You can also pass a plain library name as the override, in which case it behaves like `System.loadLibrary()` and still requires the library to be on `java.library.path`.

## Nullness Annotations

This is a Java-backend feature only. Kotlin's null-safety is part of the language; the Kotlin backend already emits nullable types as `T?` and non-null types bare, so no annotation flag is needed there.

For the Java backend, generated bindings can include [JSpecify](https://jspecify.dev/) nullness annotations so that
Kotlin consumers get proper nullable/non-null types and Java consumers get IDE and static
analysis support.

Enable in `uniffi.toml`:

```toml
[bindings.java]
nullness_annotations = true
```

When enabled:
- A `package-info.java` is generated with `@NullMarked`, making all types non-null by default
- Rust `Option<T>` types are annotated with `@Nullable`, including inside generic type
  arguments (e.g., `Map<String, @Nullable Integer>` for `HashMap<String, Option<i32>>`)
- All non-optional types (primitives, strings, records, objects, enums) are non-null

### Build Setup

JSpecify must be on the compile classpath when compiling the generated Java source.

**Gradle:**
```kotlin
// Use `api` if publishing a library so Kotlin/Java consumers benefit automatically.
// Use `compileOnly` if the bindings are only used within this project.
dependencies {
    api("org.jspecify:jspecify:1.0.0")
}
```

**Maven:**
```xml
<!-- Use default scope if publishing a library. Use <scope>provided</scope> for internal use. -->
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
    <version>1.0.0</version>
</dependency>
```

There is no runtime dependency — the JVM ignores annotation classes that are not present at
runtime.

### Kotlin consumers of Java output

Subsection on a niche scenario: a project consumes the Java backend's output from Kotlin source. (If you're starting fresh, prefer `--language kotlin` and skip this entirely.) Without nullness annotations, Kotlin sees all Java types from the generated bindings as
**platform types** (`String!`), which bypass null-safety checks. With annotations enabled,
Kotlin correctly maps:

- Non-optional types → non-null (`String`, `MyRecord`)
- `Option<T>` types → nullable (`String?`, `MyRecord?`)

This requires JSpecify to be on Kotlin's compile classpath (automatic if declared with `api`
scope).

## Notes

- failures in CompletableFutures will cause them to `completeExceptionally`. The error that caused the failure can be checked with `e.getCause()`. When implementing an async Rust trait in Java, you'll need to `completeExceptionally` instead of throwing. See `TestFixtureFutures.java` for an example trait implementation with errors. Kotlin equivalents: a failed `suspend fun` throws normally; in callback-interface impls, throwing from the user's `suspend` block surfaces as the corresponding Rust error variant via the typed-error path.
- all primitives are signed in Java by default. Rust correctly interprets a signed primitive value from Java as unsigned when told to. Callers of Uniffi functions need to be aware when making comparisons (`compareUnsigned`) or printing when a value is actually unsigned to code around footguns on this side. Kotlin has the same constraint — `Long`/`Int`/`Short`/`Byte` are signed; the wire treats them as unsigned where the Rust side is `u64`/`u32`/`u16`/`u8`. Use `Long.toULong()` etc. or `Long.compareUnsigned(...)` for unsigned semantics.
- this is an internal note for development but because Enum variants are not cases/hanging off their parent in Java, they're named standalone, so they can conflict with any/all `java.lang` types. We could do extensive checking and forced renaming around this, but instead we use fully qualified names for all `java.lang` types in all templates. Ensure that when you're making changes you're not dropping those qualified names or adding generated code without them. The same shape applies to the Kotlin backend — variants are flat-named and the templates fully-qualify `kotlin.*` and `java.lang.*` references defensively.


## Unsupported features

* Defaults aren't supported in Java so [uniffi struct, method, and function defaults](https://mozilla.github.io/uniffi-rs/proc_macro/index.html#default-values) don't exist in the Java code. *Note*: a reasonable case could be made for supporting defaults on structs by way of generated builder patterns. PRs welcome. (The Kotlin backend could in principle emit Kotlin-native default arguments, since the language supports them — also a PR-welcome enhancement.)
* Output formatting isn't currently supported because a standalone command line Java formatter wasn't found. PRs welcome enabling that feature, the infrastructure is in place. The Kotlin backend likewise emits unformatted output; running `ktlint` or `kotlinfmt` post-generation is the recommended workaround if needed.

## Testing

We pull down the pinned examples directly from Uniffi (currently v0.31.0) and run Java tests using the generated bindings. Run `cargo t` to run all of them.

The Kotlin backend has snapshot-level coverage in the default suite (string-compare against committed expected output for representative fixtures); these don't require `kotlinc` to run. A runtime smoke harness that invokes `kotlinc` and exercises the compiled output on a JVM is on the roadmap.

Note that if you need additional toml entries for your test, you can put a `uniffi-extras.toml` as a sibling of the test and it will be read in addition to the base `uniffi.toml` for the example. See [CustomTypes](./tests/scripts/TestCustomTypes/) for an example. Settings in `uniffi-extras.toml` apply across all namespaces.

## Versioning

`uniffi-bindgen-java` is versioned separately from `uniffi-rs`. We follow the [Cargo SemVer rules](https://doc.rust-lang.org/cargo/reference/resolver.html#semver-compatibility), so versions are compatible if their left-most non-zero major/minor/patch component is the same. Any modification to the generator that causes a consumer of the generated code to need to make changes is considered breaking.

`uniffi-bindgen-java` is currently unstable. It was originally developed by IronCore Labs to target features required by [`ironcore-alloy`](https://github.com/IronCoreLabs/ironcore-alloy/). The Hyeons' Lab fork extends it with an idiomatic Kotlin backend that ships in lockstep — the two backends share the same FFI runtime and wire format, so a Rust crate can produce both Java and Kotlin bindings from the same compiled cdylib. The major version is currently 0, and most changes are likely to bump the minor version.

### Compatibility

Keeping this testable requires fully pinned `uniffi-rs` versions. The version of `uniffi-rs` will always be called out in the changelog when it changes, so if you're stuck on a specific version due to other bindings, you can stay on a compatible version of these bindings.
