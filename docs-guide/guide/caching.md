# Caching

A `Cache` is a shared key-value store with expiry and atomic operations: memoizing slow lookups, one-time tokens,
locks, and counters.  Values are any `@Serializable` type.  The backend (in-memory, Redis, Memcached, DynamoDB) is
chosen by a URL in `settings.json`.

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#caching-imports -->
```kotlin
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
```

## Caching a slow lookup

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#cache-server -->
```kotlin
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
```

`get` returns `null` when the key is absent or expired; on a miss the handler fetches and stores the value for ten
minutes.  When the source data changes, `cache().remove(key)` so the next read refetches.

## Operations

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#cache-operations -->
```kotlin
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
```

| Operation | Returns | Notes |
|---|---|---|
| `get<T>(key)` | `T?` | `null` if absent or expired. |
| `set(key, value, timeToLive)` | | `timeToLive = null` (the default) never expires. |
| `remove(key)` | | No error if absent. |
| `getAndRemove<T>(key)` | `T?` | Reads and deletes; for tokens that must be used once. |
| `setIfNotExists(key, value, timeToLive)` | `Boolean` | `true` if this call stored it.  Give locks a `timeToLive`, so a crashed holder can't keep it forever, and `remove` in a `finally`. |
| `add(key, amount, timeToLive)` | `Long` / `Int` | Adds to a number (negative to subtract); a missing key starts at `amount`. |
| `modify<T>(key, maxTries, timeToLive) { old -> new }` | `Boolean` | Compare-and-swap loop; `false` if every try lost a race.  Returning `null` removes the key. |
| `compareAndSet(key, serializer, expected, new)` | `Boolean` | The primitive `modify` is built on. |

The typed overloads above infer the serializer from `T`; overloads taking a `KSerializer` exist for generic code.

For per-client request limits, use [Rate Limiting](rate-limiting.md) rather than hand-rolled counters.

## Typed keys

A `CacheKey` fixes a key's name and type in one place, so every read and write agrees:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#cache-key -->
```kotlin
val homeHits = CacheKey.OfLong("hits:home")

fun cacheKeyTest() = WeatherServer.testBlocking(settings = {}) {
    cache().add(homeHits, 1L)
    assertEquals(1L, cache().get(homeHits))
}
```

`CacheKey<T>("name")` works for any serializable type; `OfLong`, `OfInt`, and `OfInstant` also support `add`
(`OfInstant` adds a `Duration`).

There is no key namespacing beyond what you put in the key: prefix keys by feature (`"forecast:"`, `"lock:"`), or wrap
a cache in `PrefixCache(cache(), "shop:")` to share one backend between apps.

## Backends

```json
{ "cache": "redis://cache.internal:6379" }
```

| URL | Module | Register with |
|---|---|---|
| `ram` (default) | built in | |
| `ram-unsafe` (not thread-safe) | built in | |
| `redis://[:password@]host:port[/db]` | `cache-redis` | `RedisCache` |
| `memcached://host:port[,host:port]` | `cache-memcached` | `MemcachedCache` |
| `memcached-aws://config-endpoint:port` (ElastiCache auto-discovery) | `cache-memcached` | `MemcachedCache` |
| `memcached-test` (starts a local `memcached` from `PATH`) | `cache-memcached` | `MemcachedCache` |
| `dynamodb://[accessKey:secretKey@]region/table` | `cache-dynamodb` | `DynamoDbCache` |
| `dynamodb-local` (downloads and runs DynamoDB Local) | `cache-dynamodb` | `DynamoDbCache` |

Reference the class in your server's `init {}` so its scheme is registered (see
[Services](services.md#registering-a-backend)).  Only `redis://` is registered for Redis; `rediss://` is not.
Full URL syntax: [Cache URLs](https://github.com/lightningkite/service-abstractions/blob/main/docs/settings-url-reference.md#cache).

`ram` lives in one process.  With more than one server instance, use a shared backend, or each instance has its own
cache and locks don't lock.

| Backend | `setIfNotExists`, `add`, `modify` | `getAndRemove` |
|---|---|---|
| `ram` | atomic | atomic |
| Redis | atomic | atomic (`GETDEL`) |
| Memcached | atomic | read, then delete |
| DynamoDB | atomic (conditional writes) | atomic |

## Testing

Each `testBlocking` gets a fresh `ram` cache.  Expiry follows the clock in the coroutine context, so `withClock` tests
it without waiting:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#cache-ttl-test -->
```kotlin
fun cacheTtlTest() = WeatherServer.testBlocking(settings = {}) {
    val start = Instant.parse("2026-01-01T00:00:00Z")
    fun clockAt(instant: Instant) = object : Clock { override fun now() = instant }

    withClock(clockAt(start)) { cache().set("code", "1234", timeToLive = 5.minutes) }
    withClock(clockAt(start + 4.minutes)) { assertEquals("1234", cache().get<String>("code")) }
    withClock(clockAt(start + 6.minutes)) { assertNull(cache().get<String>("code")) }
}
```

## Gotchas

- `null` means absent, so a cached `null` can't be told from a miss.  Cache a wrapper if "known to be empty" matters.
- `ram` stores objects without serializing them, so a type that can't round-trip through JSON works in tests and fails
  on Redis or DynamoDB.
- `ram` drops expired entries only when they are read; keys that are written once and never read again stay in memory.
- There is no key listing or pattern delete.

## Next

- [Services](services.md): declaring services and switching backends.
- [Pub/Sub](pubsub.md): broadcasting messages between instances.
- [Rate Limiting](rate-limiting.md): per-client limits stored in the cache.
