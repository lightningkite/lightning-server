package com.lightningkite.lightningserver.guide.samples

// region caching-imports
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.withClock
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
// endregion caching-imports

// region cache-server
@Serializable
data class Forecast(val city: String, val highCelsius: Int)

object WeatherServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val cache = setting("cache", Cache.Settings())

    val forecast = path.path("forecast").post bind ApiHttpHandler(
        summary = "Get a city's forecast",
        auth = noAuth,
        implementation = { city: String ->
            val key = "forecast:$city"
            cache().get<Forecast>(key)
                ?: fetchForecast(city).also { cache().set(key, it, timeToLive = 10.minutes) }
        }
    )
}

// Stands in for a slow call to a weather API.
suspend fun fetchForecast(city: String): Forecast = Forecast(city, highCelsius = 21)
// endregion cache-server

fun forecastTest() = WeatherServer.testBlocking(settings = {}) {
    assertEquals(Forecast("Provo", 21), forecast.test(null, "Provo"))
    assertEquals(Forecast("Provo", 21), cache().get<Forecast>("forecast:Provo"))
}

// region cache-operations
fun cacheOperationsTest() = WeatherServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    val cache = cache()

    cache.set("greeting", "hello")
    assertEquals("hello", cache.get<String>("greeting"))
    cache.remove("greeting")
    assertNull(cache.get<String>("greeting"))

    // One-time token: the second read finds nothing.
    cache.set("reset:abc", "user@example.com", timeToLive = 30.minutes)
    assertEquals("user@example.com", cache.getAndRemove<String>("reset:abc"))
    assertNull(cache.getAndRemove<String>("reset:abc"))

    // Lock: only the first caller gets it.
    assertTrue(cache.setIfNotExists("lock:report", true, timeToLive = 5.minutes))
    assertFalse(cache.setIfNotExists("lock:report", true, timeToLive = 5.minutes))

    // Counter: a missing key starts at the added amount.
    assertEquals(1L, cache.add("hits:home", 1L))
    assertEquals(3L, cache.add("hits:home", 2L))

    // Read-modify-write, retried if another writer changes the value in between.
    assertTrue(cache.modify<List<String>>("recent", maxTries = 5) { (it ?: emptyList()) + "a" })
    assertEquals(listOf("a"), cache.get<List<String>>("recent"))
}
// endregion cache-operations

// region cache-key
val homeHits = CacheKey.OfLong("hits:home")

fun cacheKeyTest() = WeatherServer.testBlocking(settings = {}) {
    cache().add(homeHits, 1L)
    assertEquals(1L, cache().get(homeHits))
}
// endregion cache-key

// region cache-ttl-test
fun cacheTtlTest() = WeatherServer.testBlocking(settings = {}) {
    val start = Instant.parse("2026-01-01T00:00:00Z")
    fun clockAt(instant: Instant) = object : Clock { override fun now() = instant }

    withClock(clockAt(start)) { cache().set("code", "1234", timeToLive = 5.minutes) }
    withClock(clockAt(start + 4.minutes)) { assertEquals("1234", cache().get<String>("code")) }
    withClock(clockAt(start + 6.minutes)) { assertNull(cache().get<String>("code")) }
}
// endregion cache-ttl-test
