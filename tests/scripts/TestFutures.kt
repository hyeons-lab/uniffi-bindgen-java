import kotlinx.coroutines.runBlocking
import uniffi.uniffi_example_futures.UniffiExampleFutures

fun main() = runBlocking {
    check(UniffiExampleFutures.sayAfter(20L, "Alice") == "Hello, Alice!")
}
