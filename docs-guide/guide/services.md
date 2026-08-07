# Services & Settings

Lightning Server abstracts external services — caches, databases, file storage,
email, SMS — behind a uniform settings-driven model.  You declare which services
your server needs once, in the `ServerBuilder`, and the framework handles wiring
them up from configuration at startup.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#services-imports -->
```kotlin
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
```

## Declaring a Service Setting

Call `setting("key", ServiceType.Settings())` inside your `ServerBuilder`.
The string key becomes the field name in `settings.json`; the `Settings`
value provides the default (and describes the available backends):

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#counter-types -->
```kotlin
@Serializable
data class CounterRequest(val name: String)

@Serializable
data class CounterResponse(val name: String, val value: Long)
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#counter-server -->
```kotlin
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
```

A few things to note:

- **`cache`** is a `ServerSetting` — a lazy handle, not the live service.
  Calling `cache()` inside a handler resolves it against the running runtime.
  Calling it at module-load time (e.g., at the top of a companion object) would
  crash because no runtime exists yet.
- **`Cache.Settings()`** takes a URL string.  The default is `"ram"`, which is
  built in and needs no external infrastructure.  Other backends are registered
  by their implementation modules: `"redis://…"`, `"memcached://…"`.  Switching
  backends is a configuration change, not a code change.
- **All service types follow the same shape**: `Database.Settings()`,
  `PublicFileSystem.Settings()`, `EmailService.Settings()` — one `setting()`
  declaration per service, one URL string in settings.json.

## Using Non-Default Backends: Reference Them Early

The default URL for every service type (`"ram"` for Cache, `"ram"` for
Database, `"local"` for files, etc.) is built into the core module and
registers itself automatically.  Non-default backends — MongoDB, Redis,
Memcached, DynamoDB, S3, SES, Twilio — live in separate modules.  Each
backend's companion object (or top-level object) registers a URL scheme
handler when it is first referenced by the JVM class loader.

**If the backend object is never referenced, its URL scheme is never
registered**, and a `settings.json` that points at that backend (e.g.
`"mongodb://…"`) will fail to parse at startup with an
`UnknownUrlScheme`-style error.

The fix is to touch each backend object you might use in an `init {}` block
inside your `ServerBuilder`, *before* settings are loaded:

```kotlin
// Illustrative — from demo/src/main/kotlin/.../Server.kt.
// The sole purpose of these statements is to ensure each companion initializer
// runs so that its URL scheme handler is registered before settings.json loads.
// None of these objects need to be assigned to a variable.
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", PublicFileSystem.Settings())

    init {
        MongoDatabase       // registers "mongodb://"
        MemcachedCache      // registers "memcached://"
        DynamoDbCache       // registers "dynamodb://"
        S3PublicFileSystem  // registers "s3://"
        JsonFileDatabase    // registers "jsonfile://"
        // ... and so on for any backend your settings.json might name
    }
}
```

A few details:

- **Order matters** — the `init {}` block runs when the `object` is first
  accessed, which happens before `loadFromFile` is called.  Referencing
  backend objects anywhere before settings load is fine; `init {}` is the
  canonical place.
- **You don't have to reference ALL backends** — only those you might
  configure in `settings.json`.  The demo server references all of them so
  that any settings file works without a code change.
- **Tests that use `"ram"`** never need this, because the RAM backends are
  always registered.

> This is illustrative prose; the URL-scheme registration mechanism is
> verified against `demo/src/main/kotlin/.../Server.kt` (init block, lines
> ~87–102).  No compiled sample region is added here because the backends
> (MongoDatabase, S3, etc.) are not available in the docs-guide module.

## The settings.json File

When you run your server for the first time with `loadFromFile(KFile("settings.json"), ...)`,
Lightning Server writes a generated `settings.json` with all declared defaults and
then exits with a `MissingSettingFile` exception.  Run again and it reads the
file normally:

```json
{
  "cache": "ram"
}
```

Every declared `setting(key, default)` produces one line.  Edit the value to
point at a real backend — the server code never changes.

> **Note:** This JSON block is illustrative — it matches the output you would
> see for `CounterServer` above, but it is not a drift-checked sample region
> because settings.json is a generated file, not a compiled Kotlin source.

## Configuring Services in Tests

The `test {}` block's `settings` lambda gives you a `ServerSettings` receiver
in which you can override any declared setting before the runtime starts.  Use
the infix `set` to supply a settings value.

> To wrap these examples in a test class, annotate your test methods with `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` + `testBlocking` pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#counter-test -->
```kotlin
// Override the setting inside the settings lambda so the test uses
// a fresh in-memory cache. "ram" is a built-in URL that resolves to a
// ConcurrentHashMap-backed MapCache — no external service needed.
fun counterTest() = CounterServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    // Call increment twice; the counter should reach 2.
    CounterServer.increment.test(null, CounterRequest("hits"))
    val result = CounterServer.increment.test(null, CounterRequest("hits"))
    check(result.value == 2L)

    // A counter that was never incremented reads as 0.
    val missing = CounterServer.read.test(null, CounterRequest("unset"))
    check(missing.value == 0L)
}
```

The `cache set Cache.Settings("ram")` call explicitly sets the `cache` setting
to the in-memory backend.  This is slightly redundant for `Cache` because its
default is already `"ram"`, but being explicit protects the test from breaking
if the `ServerBuilder` default ever changes to a production backend.
Spelling it out makes the `"ram"` URL visible in the test itself, so the reader
can see at a glance which backend is active.

Each `test {}` call creates a fresh runtime — a fresh `MapCache` instance in
this case — so tests never share state.  No cleanup code needed.

The `null` first argument to `.test(null, ...)` is the auth token; `null` is
correct for `noAuth` endpoints (see Chapter 1 for a fuller explanation).

## What's Next

- **Database** — declare `val database = setting("database", Database.Settings())`
  and use `"ram"` in tests.  Chapter 5 covers the type-safe query DSL for
  inserting, finding, and updating documents.
- **Files** — `PublicFileSystem.Settings()` provides presigned URLs and
  multipart upload support; the `"local"` backend writes to a temp directory.
- **Multiple services** — any number of settings can be declared in a single
  `ServerBuilder`; override as many as needed in the `settings = { }` lambda.
