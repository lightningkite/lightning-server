> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Caching Patterns

Lightning Server's cache abstraction wraps external key-value stores — RAM, Redis,
Memcached, DynamoDB — behind a single `Cache` interface.  Values are serialised
with KotlinX Serialization.  Swapping backends is a config-file change; no code
changes required.

## Imports

All examples in this chapter use:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.services.cache.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
```

## Declaring the Cache

```kotlin
object Server : ServerBuilder() {
    // "cache" becomes the key in settings.json.
    // Cache.Settings() defaults to "ram" — a ConcurrentHashMap-backed in-process cache.
    val cache = setting("cache", Cache.Settings())
}
```

Call `cache()` inside any handler or task to get the live `Cache` instance.
`cache` itself is a lazy handle; calling it at module-load time (outside a handler)
will crash because no runtime exists yet.

## Basic `get` and `set`

The reified extension functions on `Cache` infer the serialiser from the type parameter,
so you never need to supply one manually.  The non-reified overloads (which take an
explicit `KSerializer<T>`) exist for advanced use and for writing generic helpers.

```kotlin
// Store a value; no TTL — lives until evicted or the process restarts
cache().set("greeting", "hello")

// Retrieve it — returns null if absent or expired
val greeting: String? = cache().get<String>("greeting")

// Store with a TTL
cache().set("session:abc", sessionData, timeToLive = 1.hours)

// Serialises any @Serializable type
@Serializable
data class UserPrefs(val theme: String, val language: String)

cache().set("prefs:${userId}", UserPrefs("dark", "en"), timeToLive = 24.hours)
val prefs: UserPrefs? = cache().get<UserPrefs>("prefs:${userId}")
```

`get` returns `null` on a cache miss (key absent or TTL elapsed).  There is no
distinction between "never set" and "expired".

## Cache-Aside Pattern

Cache-aside is the standard read strategy: check the cache first; on a miss,
load from the source of truth and populate the cache.

```kotlin
suspend fun getUser(id: Uuid): User {
    val key = "user:$id"

    // 1. Try the cache
    val cached: User? = cache().get<User>(key)
    if (cached != null) return cached

    // 2. Miss — load from database
    val user = database().table<User>().get(id)
        ?: throw NotFoundException("user $id not found")

    // 3. Populate cache for future reads
    cache().set(key, user, timeToLive = 15.minutes)

    return user
}
```

On writes, invalidate (or update) the cached entry so stale data is not served:

```kotlin
suspend fun updateUser(id: Uuid, modification: Modification<User>): User {
    val updated = database().table<User>().updateOneById(id, modification)
        ?: throw NotFoundException("user $id not found")

    // Invalidate the cache entry — next read will reload from DB
    cache().remove("user:$id")

    return updated
}
```

## `getAndRemove` — One-Time Tokens

`getAndRemove` retrieves a value and atomically deletes it in one step.  It is
the right tool for one-time tokens (email verification links, password-reset
codes) where the token must be consumed on first use.

```kotlin
// Store a verification token with a 30-minute window
val token = Uuid.random().toString()
cache().set("verify:$token", userId, timeToLive = 30.minutes)

// Later — consume the token (returns null if already used or expired)
val userId: Uuid? = cache().getAndRemove<Uuid>("verify:$token")
if (userId == null) throw UnauthorizedException("invalid or expired token")
```

> **Atomicity note:** Redis and DynamoDB backends implement `getAndRemove` as a
> single atomic operation (`GETDEL` / `DeleteItem`).  The base-interface default
> is a non-atomic read-then-delete; for `ram` (in-process) it is effectively
> atomic because there is no concurrency between the two steps within a single
> process, but do not rely on this in multi-instance deployments.

## Distributed Locks via `setIfNotExists`

`setIfNotExists` writes a value only if the key is absent, and returns `true` if
the write succeeded.  This is the building block for distributed mutex locks.

```kotlin
val lockKey = "lock:report:monthly"
val acquired = cache().setIfNotExists(
    key = lockKey,
    value = "locked",
    timeToLive = 5.minutes,   // auto-release if the process crashes
)

if (!acquired) {
    // Another instance already holds the lock — skip or wait
    return
}

try {
    generateMonthlyReport()
} finally {
    cache().remove(lockKey)
}
```

The `timeToLive` guard is essential: without it, a crash while the lock is held
would leave it permanently set and block all future attempts.

> **Backend note:** Redis and Memcached implement `setIfNotExists` as a single
> atomic `SET NX` / `add` command.  The DynamoDB backend uses a conditional
> `PutItem`.  The `ram` in-process backend uses `ConcurrentHashMap.putIfAbsent`.
> All are safe for single-key exclusion within their respective scopes.

## Atomic Counters via `add`

`add` atomically increments (or decrements) a numeric value.  If the key does
not exist, it is created with the given value as its initial count.

```kotlin
// Increment a page-view counter; returns the new value
val views: Long = cache().add("views:article:$articleId", 1L)

// Decrement (pass a negative value)
val remaining: Long = cache().add("seats:event:$eventId", -1L)

// Int overload — returns Int
val score: Int = cache().add("score:${userId}", 10)
```

`add` is the right tool for any monotonically changing numeric state that does
not need compare-and-swap semantics (rate limiters, hit counters, queued-job
counts).

> The return value is the value *after* the addition.  Most backends maintain
> the original stored type; some (notably Redis) normalise to `Long`.

### Rate Limiting with `add`

```kotlin
suspend fun checkRateLimit(userId: String, windowSeconds: Int, maxRequests: Int): Boolean {
    val key = "ratelimit:$userId:${Clock.System.now().epochSeconds / windowSeconds}"
    val count = cache().add(key, 1L, timeToLive = windowSeconds.seconds)
    return count <= maxRequests
}
```

## Compare-and-Swap via `modify`

`modify` reads the current value, applies a transformation, and writes back
using a compare-and-swap loop, retrying up to `maxTries` times on conflicts.
If `modification` returns `null`, the key is deleted.

```kotlin
// Safe increment of a structured value
cache().modify<UserSession>(
    key = "session:$sessionId",
    maxTries = 5,
    timeToLive = 30.minutes,
) { current ->
    current?.copy(lastSeenAt = Clock.System.now())
        ?: UserSession(id = sessionId, lastSeenAt = Clock.System.now())
}

// Delete the entry when a condition is met
cache().modify<Cart>(
    key = "cart:$userId",
    maxTries = 3,
) { cart ->
    if (cart?.items.isNullOrEmpty()) null else cart  // removes empty cart
}
```

`modify` returns `true` if the write succeeded within `maxTries` attempts, `false`
otherwise.  A return value of `false` is safe to ignore when the worst outcome is
a stale counter; treat it as an error when correctness is required.

> **Backend note:** Redis uses a Lua script for true CAS atomicity.  Memcached
> uses GETS/CAS tokens.  DynamoDB and the `ram` backend use retry-based
> compare-and-set.  The number of retries needed in practice depends on write
> contention.

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

Add the `cache-redis` module and touch `RedisCache` in an `init` block so its
URL-scheme handler is registered before `settings.json` loads.

```kotlin
import com.lightningkite.services.cache.redis.RedisCache

object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        RedisCache   // registers the "redis://" and "rediss://" schemes
    }
}
```

```json
{ "cache": "redis://localhost:6379" }
```

Common URL forms:

| URL | Effect |
|-----|--------|
| `redis://host:6379` | Unencrypted |
| `redis://user:pass@host:6379` | Authenticated |
| `rediss://host:6380` | TLS/SSL |
| `redis-sentinel://host1:26379,host2:26379/mymaster` | Sentinel HA |

Redis `setIfNotExists` and `modify` use Lua scripts for true atomicity.

### Memcached

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

> Memcached TTL precision is seconds (not milliseconds).  Sub-second TTLs are
> rounded up.

### DynamoDB

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
  filters them out manually.
- No native CAS; `modify` uses the retry-based default implementation.

## Using Non-Default Backends: Register Early

The `ram` backend is always registered.  Every other backend registers its URL
scheme when its companion object (or top-level singleton) is first touched by the
JVM class loader.  If you skip the `init` block, a `settings.json` pointing at
`"redis://…"` will fail to parse at startup.

```kotlin
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        // Touch each backend you might ever configure in settings.json.
        RedisCache
        MemcachedCache
        DynamoDbCache
    }
}
```

This is a one-time initialisation step.  `init` runs when the `object` is first
accessed, which is before `loadFromFile` reads `settings.json`.

## Testing

Use `Cache.Settings("ram")` in your `testBlocking` settings lambda.  Each test
call creates a fresh `MapCache` instance — no shared state, no cleanup needed.

```kotlin
fun myTest() = Server.testBlocking(
    settings = { cache set Cache.Settings("ram") }
) {
    // set + get round-trip
    cache().set("key", "value")
    val result: String? = cache().get<String>("key")
    check(result == "value")

    // TTL is not simulated in RAM; items persist for the duration of the test.
}
```

> Being explicit about `Cache.Settings("ram")` is slightly redundant (it is
> already the default) but protects the test from breaking if the server's
> default is ever changed to a production backend.

## Key Design Notes

- **Null means absent** — `get` returns `null` for both "never set" and "expired".
  There is no way to distinguish them.
- **No scan / list** — the `Cache` interface does not expose key enumeration or
  pattern-delete.  These operations are backend-specific and intentionally omitted.
- **Serialisation** — values are serialised using the server's
  `internalSerializersModule`.  Any `@Serializable` type works; primitive types
  (`String`, `Int`, `Long`, `Boolean`, etc.) work without annotation.
- **Key namespacing** — there is no built-in key namespacing.  Use
  conventional prefixes (`"user:"`, `"lock:"`, `"ratelimit:"`) to avoid
  collisions between unrelated features.

## See Also

- [Services & Settings](../guide/services.md) — how `setting()` declarations work and the URL-scheme registration model
- [Tasks](../guide/tasks.md) — background jobs that often use the cache for deduplication
- [Sessions](../guide/auth.md) — the auth session layer uses the cache internally for token storage
