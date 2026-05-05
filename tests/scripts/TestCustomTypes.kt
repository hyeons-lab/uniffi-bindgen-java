// Runtime test for the upstream `custom-types` example. Validates
// the `[bindings.kotlin.custom_types.Url]` config in the fixture's
// `uniffi.toml` (lift via `URI({}).toURL()`, lower via
// `{}.toString()`), plus the unconfigured custom types (Handle,
// TimeIntervalMs, TimeIntervalSecDbl, TimeIntervalSecFlt) which fall
// back to wrapping their inner Rust value.
import customtypes.CustomTypes
import customtypes.CustomTypesDemo
import customtypes.Handle
import customtypes.TimeIntervalMs
import customtypes.TimeIntervalSecDbl
import customtypes.TimeIntervalSecFlt
import customtypes.Url
import java.net.URI

fun main() {
    val demo = CustomTypes.getCustomTypesDemo(null)
    check(demo.url == Url(URI("http://example.com/").toURL()))
    check(demo.handle == Handle(123L))

    // Round-trip: pass back what we got and verify the Rust side
    // sees the same data after lower-then-lift.
    check(CustomTypes.getCustomTypesDemo(demo) == demo)

    // Modify two custom-typed fields and round-trip again. Kotlin's
    // `data class` is immutable (val fields), so use `.copy(...)`.
    val updated = demo.copy(
        url = Url(URI("http://new.example.com/").toURL()),
        handle = Handle(456L),
    )
    check(CustomTypes.getCustomTypesDemo(updated) == updated)
    check(updated.url == Url(URI("http://new.example.com/").toURL()))
    check(updated.handle == Handle(456L))

    // The default demo also carries the time-interval custom types;
    // round-tripping the demo above already validates lift+lower for
    // these wrappers — no extra inner-value asserts needed.
}
