# Caching

Lightning Server's cache abstraction wraps external key-value stores — RAM, Redis,
Memcached, DynamoDB — behind a single `Cache` interface.  Values are serialised
with KotlinX Serialization.  Swapping backends is a config-file change; no code
changes required.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#caching-imports -->
```kotlin
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.services.cache.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
```

`com.lightningkite.lightningserver.runtime.test.*` provides `testBlocking`.
`com.lightningkite.lightningserver.settings.*` provides the `set` infix used inside
the `testBlocking` settings lambda to configure which backend a setting resolves to.

## Declaring the Cache

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#cache-server -->
```kotlin
object CacheServer : ServerBuilder() {
    // "cache" becomes the key in settings.json.
    // Cache.Settings() defaults to "ram" — a ConcurrentHashMap-backed in-process cache.
    val cache = setting("cache", Cache.Settings())
}
```

Call `cache()` inside any handler or task body to get the live `Cache` instance.
`cache` itself is a lazy handle; calling it at module-load time (outside a handler
or test runtime) will crash because no `ServerRuntime` exists yet.

## Basic `get` and `set`

The reified extension functions on `Cache` infer the serialiser from the type parameter,
so you never need to supply one manually.  The non-reified overloads (which take an
explicit `KSerializer<T>`) exist for writing generic helpers.

```kotlin
// Illustrative — not a drift-checked sample.
// All cache calls require a ServerRuntime in context (inside a handler or testBlocking).

// Store a string; no TTL — lives until evicted or the process restarts.
cache().set("greeting", "hello")

// Retrieve it — returns null if absent or expired.
val greeting: String? = cache().get<String>("greeting")

// Store with a TTL — the key is automatically removed after 1 hour.
cache().set("session:${token}", sessionData, timeToLive = 1.hours)

// Any @Serializable type works; the reified extension infers the serializer.
cache().set("prefs:${userId}", userPrefs, timeToLive = 24.hours)
val prefs: UserPrefs? = cache().get<UserPrefs>("prefs:${userId}")
```

`get` returns `null` on a cache miss (key absent or TTL elapsed).  There is no
distinction between "never set" and "expired".

## Cache-Aside Pattern

Cache-aside is the standard read strategy: check the cache first; on a miss, load
from the source of truth and populate the cache.

```kotlin
// Illustrative — not a drift-checked sample.
// Requires ServerRuntime in context; in production, annotate with context(server: ServerRuntime).

suspend fun getUser(id: Uuid): User {
    val key = "user:$id"

    // 1. Try the cache first
    val cached: User? = cache().get<User>(key)
    if (cached != null) return cached

    // 2. Miss — load from the source of truth
    val user = database().table<User>().get(id)
        ?: throw NotFoundException("user $id not found")

    // 3. Populate cache for future reads
    cache().set(key, user, timeToLive = 15.minutes)
    return user
}
```

On writes, invalidate or update the cached entry so stale data is not served:

```kotlin
// Illustrative — not a drift-checked sample.
suspend fun updateUser(id: Uuid, modification: Modification<User>): User {
    val updated = database().table<User>().updateOneById(id, modification)
        ?: throw NotFoundException("user $id not found")

    // Invalidate — next read will reload from the database.
    cache().remove("user:$id")
    return updated
}
```

## `getAndRemove` — One-Time Tokens

`getAndRemove` retrieves a value and atomically deletes it in one step.  It is the
right tool for one-time tokens (email verification links, password-reset codes) where
the token must be consumed on first use.

```kotlin
// Illustrative — not a drift-checked sample.

// Store a verification token with a 30-minute window.
val token = Uuid.random().toString()
cache().set("verify:$token", userId, timeToLive = 30.minutes)

// Later — consume the token (returns null if already used or expired).
val userId: Uuid? = cache().getAndRemove<Uuid>("verify:$token")
if (userId == null) throw UnauthorizedException("invalid or expired token")
```

> **Atomicity note:** Redis and DynamoDB backends implement `getAndRemove` as a
> single atomic operation (`GETDEL` / `DeleteItem`).  The `ram` in-process backend
> also atomically removes the entry.  The base-interface default is non-atomic
> (read then delete) — rely on atomicity only when using a backend that documents it.

## `setIfNotExists` — Distributed Locks

`setIfNotExists` writes a value only if the key is absent, and returns `true` if the
write succeeded.  This is the building block for distributed mutex locks.

```kotlin
// Illustrative — not a drift-checked sample.
val lockKey = "lock:monthly-report"
val acquired = cache().setIfNotExists(
    key = lockKey,
    value = "locked",
    timeToLive = 5.minutes,   // auto-release if the process crashes
)

if (!acquired) {
    // Another instance already holds the lock — skip this run.
    return
}

try {
    generateMonthlyReport()
} finally {
    cache().remove(lockKey)
}
```

The `timeToLive` guard is essential: without it, a crash while the lock is held would
leave it permanently set and block all future attempts.

> **Backend note:** Redis and Memcached implement `setIfNotExists` as a single atomic
> `SET NX` / `add` command.  DynamoDB uses a conditional `PutItem`.  The `ram`
> backend uses `ConcurrentHashMap.compute` for atomicity within a single process.

## Atomic Counters via `add`

`add` atomically increments (or decrements) a numeric value.  If the key does not
exist, it is created with the given value as its initial count.

```kotlin
// Illustrative — not a drift-checked sample.

// Increment a page-view counter; returns the new value.
val views: Long = cache().add("views:article:$articleId", 1L)

// Decrement (pass a negative value).
val remaining: Long = cache().add("seats:event:$eventId", -1L)

// Int overload — returns Int.
val score: Int = cache().add("score:${userId}", 10)
```

`add` is the right tool for any monotonically changing numeric state that does not
need compare-and-swap semantics (rate limiters, hit counters, queue depths).

### Rate Limiting with `add`

```kotlin
// Illustrative — not a drift-checked sample.
// Requires ServerRuntime in context.
suspend fun checkRateLimit(userId: String, windowSeconds: Int, maxRequests: Int): Boolean {
    val key = "ratelimit:$userId:${Clock.System.now().epochSeconds / windowSeconds}"
    val count = cache().add(key, 1L, timeToLive = windowSeconds.seconds)
    return count <= maxRequests
}
```

## Compare-and-Swap via `modify`

`modify` reads the current value, applies a transformation, and writes back using a
compare-and-swap loop, retrying up to `maxTries` times on conflicts.  If the
`modification` lambda returns `null`, the key is deleted.

```kotlin
// Illustrative — not a drift-checked sample.

// Safe increment of a counter even under concurrent writers.
cache().modify<Int>(
    key = "page-views",
    maxTries = 5,
) { current ->
    (current ?: 0) + 1
}

// Delete the entry when a condition is met; return null to remove the key.
cache().modify<String>(
    key = "cart:${userId}",
    maxTries = 3,
) { current ->
    if (current.isNullOrEmpty()) null else current
}
```

`modify` returns `true` if the write succeeded within `maxTries` attempts.  A return
value of `false` is safe to ignore for best-effort counters; treat it as an error
when correctness is required.

> **Backend note:** Redis uses a Lua script for true CAS atomicity.  Memcached uses
> GETS/CAS tokens.  The `ram` backend uses `ConcurrentHashMap.compute`.  The default
> interface implementation is a non-atomic read-then-compare loop — backends should
> override with native CAS where available.

## Backends

### Built-in: `ram` and `ram-unsafe`

Both are always registered; no extra module required.

| URL | Description |
|-----|-------------|
| `ram` (default) | `ConcurrentHashMap`-backed; thread-safe; per-process only. |
| `ram-unsafe` | Plain `HashMap`; no locking; single-threaded use only. |

`ram` is the right default for tests.  It is not suitable for multi-instance
production deployments because each process has a separate in-memory map.

```json
{ "cache": "ram" }
```

### Redis

Illustrative — not a drift-checked sample.  Requires the `cache-redis` module.

```kotlin
import com.lightningkite.services.cache.redis.RedisCache

object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        RedisCache   // touches the object; registers "redis://" and "rediss://" schemes
    }
}
```

```json
{ "cache": "redis://localhost:6379" }
```

| URL | Effect |
|-----|--------|
| `redis://host:6379` | Unencrypted |
| `redis://user:pass@host:6379` | Authenticated |
| `rediss://host:6380` | TLS/SSL |
| `redis-sentinel://host1:26379,host2:26379/mymaster` | Sentinel HA |

Redis `setIfNotExists` and `modify` use Lua scripts for true atomicity.

### Memcached

Illustrative — not a drift-checked sample.  Requires the `cache-memcached` module.

```kotlin
import com.lightningkite.services.cache.memcached.MemcachedCache

object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        MemcachedCache   // registers "memcached://", "memcached-aws://", "memcached-test://"
    }
}
```

```json
{ "cache": "memcached://localhost:11211" }
```

| URL | Effect |
|-----|--------|
| `memcached://host:11211` | Single server |
| `memcached://h1:11211,h2:11211` | Multi-server with automatic sharding |
| `memcached-aws://config.cache.amazonaws.com:11211` | AWS ElastiCache |
| `memcached-test://` | Embedded instance for integration tests |

> Memcached TTL precision is seconds; sub-second TTLs are rounded up.

### DynamoDB

Illustrative — not a drift-checked sample.  Requires the `cache-dynamodb` module.

```kotlin
import com.lightningkite.services.cache.dynamodb.DynamoDbCache

object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        DynamoDbCache   // registers "dynamodb://" and "dynamodb-local://"
    }
}
```

```json
{ "cache": "dynamodb://us-east-1/my-cache-table" }
```

| URL | Effect |
|-----|--------|
| `dynamodb://region/table` | IAM-role / environment credentials |
| `dynamodb://key:secret@region/table` | Explicit credentials |
| `dynamodb-local://` | DynamoDB Local for integration tests |

DynamoDB notes:
- Tables are created automatically on first access (~10–30 s delay).
- TTL is best-effort; expired items may persist for minutes to hours.  `get`
  filters them out client-side.
- No native CAS; `modify` uses the retry-based default implementation.

### Using Non-Default Backends: Register Early

The `ram` backend is always registered.  Every other backend registers its URL
scheme when its companion object (or top-level singleton) is first touched by the
JVM class loader.  If you skip the `init` block, a `settings.json` pointing at
`"redis://…"` will fail to parse at startup.

Touch each backend you intend to configure in a single `init` block:

```kotlin
// Illustrative — not a drift-checked sample.
init {
    RedisCache
    MemcachedCache
    DynamoDbCache
}
```

## Testing

Use `Cache.Settings("ram")` in your `testBlocking` settings lambda.  Each
`testBlocking` call creates a fresh `MapCache` instance — no shared state, no
cleanup needed between tests.

<!-- sample: com/lightningkite/lightningserver/guide/samples/CachingSamples.kt#cache-test -->
```kotlin
fun cacheTest() = CacheServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    val c = CacheServer.cache()

    // set + get round-trip
    c.set("greeting", "hello")
    assertEquals("hello", c.get<String>("greeting"))

    // null on a miss
    assertNull(c.get<String>("no-such-key"))

    // overwrite: set replaces the existing value
    c.set("greeting", "world")
    assertEquals("world", c.get<String>("greeting"))

    // remove
    c.remove("greeting")
    assertNull(c.get<String>("greeting"))

    // getAndRemove: returns the value and atomically deletes the key in one step
    c.set("verify:token-abc", "user@example.com", timeToLive = 30.minutes)
    assertEquals("user@example.com", c.getAndRemove<String>("verify:token-abc"))
    assertNull(c.getAndRemove<String>("verify:token-abc"))  // key is now gone

    // setIfNotExists: write only if the key is absent — the basis for distributed locks
    assertTrue(c.setIfNotExists("lock:report", "locked", timeToLive = 5.minutes))
    assertFalse(c.setIfNotExists("lock:report", "locked", timeToLive = 5.minutes))  // already exists

    // add: atomic increment; key is created when it first appears
    assertEquals(1L, c.add("hits:page1", 1L))
    assertEquals(2L, c.add("hits:page1", 1L))
    assertEquals(1L, c.add("hits:page1", -1L))  // decrement
}
```

Being explicit about `Cache.Settings("ram")` protects the test from breaking if the
server's default is ever changed to a production backend.

TTL is not simulated in the `ram` backend — items persist for the duration of the
`testBlocking` call.  If your code under test relies on TTL-based expiry, you will
need to drive expiry manually (e.g., by calling `remove`) or use a backend that
supports it.

## Key Design Notes

- **Null means absent** — `get` returns `null` for both "never set" and "expired".
  There is no way to distinguish them.
- **No scan / list** — the `Cache` interface does not expose key enumeration or
  pattern-delete.  These operations are backend-specific and intentionally omitted.
- **Serialisation** — values are serialised using the server's
  `internalSerializersModule`.  Any `@Serializable` type works; primitive types
  (`String`, `Int`, `Long`, `Boolean`, etc.) work without annotation.
- **Key namespacing** — there is no built-in key namespacing.  Use conventional
  prefixes (`"user:"`, `"lock:"`, `"ratelimit:"`) to avoid collisions between
  unrelated features.

## See Also

- [Services & Settings](services.md) — how `setting()` declarations work and the
  URL-scheme registration model
- [Tasks](tasks.md) — background jobs that often use the cache for deduplication
- [Authentication & Sessions](auth.md) — the auth session layer uses the cache
  internally for token storage
