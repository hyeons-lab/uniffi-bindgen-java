// Runtime test for the upstream `custom-types` example. Validates
// the `[bindings.kotlin.custom_types.Url]` config in the fixture's
// `uniffi.toml` (lift via `URI({}).toURL()`, lower via
// `{}.toString()`), plus round-trip behaviour of the unconfigured
// custom types (Handle, TimeIntervalMs, TimeIntervalSecDbl,
// TimeIntervalSecFlt) which fall back to wrapping their inner Rust
// value as `data class`es.
import customtypes.CustomTypes
import customtypes.Handle
import customtypes.Url
import java.net.URI

fun main() {
    val demo = CustomTypes.getCustomTypesDemo(null)
    // `Url` is a `data class(val value: URL)`, so its auto-derived
    // `equals` would call `URL.equals()` — which triggers DNS lookups
    // and is non-deterministic in CI. Compare by string form instead.
    check(demo.url.value.toExternalForm() == "http://example.com/")
    check(demo.handle == Handle(123L))

    // Round-trip via lower → Rust → lift. Compare scalar fields
    // directly (and Url via its string form) to avoid the `URL.equals`
    // DNS path.
    val rt = CustomTypes.getCustomTypesDemo(demo)
    check(rt.url.value.toExternalForm() == demo.url.value.toExternalForm())
    check(rt.handle == demo.handle)
    check(rt.timeIntervalMs == demo.timeIntervalMs)
    check(rt.timeIntervalSecDbl == demo.timeIntervalSecDbl)
    check(rt.timeIntervalSecFlt == demo.timeIntervalSecFlt)

    // Modify two custom-typed fields and round-trip again. Kotlin's
    // `data class` is immutable (val fields), so use `.copy(...)`.
    val updated = demo.copy(
        url = Url(URI("http://new.example.com/").toURL()),
        handle = Handle(456L),
    )
    val updatedRt = CustomTypes.getCustomTypesDemo(updated)
    check(updatedRt.url.value.toExternalForm() == "http://new.example.com/")
    check(updatedRt.handle == Handle(456L))
}
