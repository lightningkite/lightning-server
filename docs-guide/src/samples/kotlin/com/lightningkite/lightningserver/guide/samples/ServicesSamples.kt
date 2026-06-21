package com.lightningkite.lightningserver.guide.samples

// region services-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
// endregion services-imports

// region counter-types
@Serializable
data class CounterRequest(val name: String)

@Serializable
data class CounterResponse(val name: String, val value: Long)
// endregion counter-types

// region counter-server
object CounterServer : ServerBuilder() {
    // Declare the cache service. The string "cache" becomes the key in settings.json.
    // Cache.Settings() defaults to "ram" — an in-process map backed by ConcurrentHashMap.
    val cache = setting("cache", Cache.Settings())

    // POST /counter/increment — adds 1 to a named counter stored in the cache
    val increment = path.path("counter").path("increment").post bind ApiHttpHandler(
        summary = "Increment a named counter",
        description = "Adds 1 to the named counter and returns the new value.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { input: CounterRequest ->
            // Access the live cache instance by invoking the setting: cache()
            // cache() is callable anywhere a ServerRuntime context is available,
            // which includes every handler implementation lambda.
            val newValue = cache().add("counter:${input.name}", 1L)
            CounterResponse(name = input.name, value = newValue)
        }
    )

    // POST /counter/read — returns the current counter value without incrementing
    val read = path.path("counter").path("read").post bind ApiHttpHandler(
        summary = "Read a named counter",
        description = "Returns the current value of the named counter, or 0 if it has never been set.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { input: CounterRequest ->
            // Cache.get<T>() returns null when the key is absent; coerce to 0.
            val value = cache().get<Long>("counter:${input.name}") ?: 0L
            CounterResponse(name = input.name, value = value)
        }
    )
}
// endregion counter-server

// region counter-test
fun counterTest() = runBlocking {
    // Override the setting inside the settings lambda so the test uses
    // a fresh in-memory cache. "ram" is a built-in URL that resolves to a
    // ConcurrentHashMap-backed MapCache — no external service needed.
    CounterServer.test(settings = { cache set Cache.Settings("ram") }) {
        // Call increment twice; the counter should reach 2.
        CounterServer.increment.test(null, CounterRequest("hits"))
        val result = CounterServer.increment.test(null, CounterRequest("hits"))
        check(result.value == 2L)

        // A counter that was never incremented reads as 0.
        val missing = CounterServer.read.test(null, CounterRequest("unset"))
        check(missing.value == 0L)
    }
}
// endregion counter-test
