# Services

Databases, caches, file storage, email, SMS, and pub/sub are *services*: you declare one as a setting, and a URL in
`settings.json` picks its backend.  Code written against `Cache` runs unchanged on an in-memory map in tests and on
Redis in production.

The service interfaces and their backends come from
[Service Abstractions](https://github.com/lightningkite/service-abstractions); this chapter covers how a Lightning
Server declares and uses them.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#services-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import kotlin.test.*
```

## A server with a cache

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#counter-server -->
```kotlin
object CounterServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val cache = setting("cache", Cache.Settings())

    val increment = path.path("counter").post bind ApiHttpHandler(
        summary = "Increment a named counter",
        auth = noAuth,
        implementation = { name: String -> cache().add("counter:$name", 1L) }
    )
}
```

`setting("cache", Cache.Settings())` declares a setting named `cache` whose default is `Cache.Settings()`, the URL
`"ram"`.  The value it returns is a handle, not a cache: call `cache()` to get the live `Cache`.  The call needs a
runtime in context, which handlers, tasks, schedules, startup tasks, and `testBlocking` bodies have; the compiler
rejects it anywhere else.  The service is built once per runtime and reused.

The setting becomes one line of `settings.json`:

```json
{
  "cache": "ram"
}
```

## Switching backends

Change the URL; the code stays the same:

```json
{
  "cache": "redis://cache.internal:6379"
}
```

Every service follows the same pattern.  The defaults need no infrastructure:

| Service | Declaration | Default URL |
|---|---|---|
| Database | `setting("database", Database.Settings())` | `ram` |
| Cache | `setting("cache", Cache.Settings())` | `ram` |
| Pub/sub | `setting("pubSub", PubSub.Settings())` | `local` |
| File storage | `setting("files", ExternalFileSystem.Settings())` | `file://<working dir>/local/files?serveUrl=files` |
| Email | `setting("email", EmailService.Settings())` | `console` |
| SMS | `setting("sms", SMS.Settings())` | `console` |
| Push notifications | `setting("notifications", NotificationService.Settings("test"))` | none; pass one |

Every URL scheme each service accepts is listed in Service Abstractions'
[Settings URL Reference](https://github.com/lightningkite/service-abstractions/blob/main/docs/settings-url-reference.md).

## Registering a backend

`ram`, `local`, `console` and the other defaults are built in.  Every other backend lives in its own module and
registers its URL scheme when its class is first initialized, so add the dependency and touch the class before
settings load:

```kotlin
// build.gradle.kts
implementation("com.lightningkite.services:cache-redis:<version>")
```

```kotlin
import com.lightningkite.services.cache.redis.RedisCache
import com.lightningkite.services.database.mongodb.MongoDatabase

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    init {
        // Referencing each class runs its companion's init, which registers its URL scheme.
        MongoDatabase
        RedisCache
    }
}
```

Without this, startup fails and logs `No handler redis for ... - available handlers are ...`: the module may be on the
classpath, but its scheme was never registered.  Reference every backend any of your settings files might name; tests on the
built-in defaults need none.  The [Settings URL Reference](https://github.com/lightningkite/service-abstractions/blob/main/docs/settings-url-reference.md#cheatsheet)
names the class to reference for each scheme.

## Two of the same service

Settings are independent, so two caches are two settings:

```kotlin
val cache = setting("cache", Cache.Settings())
val sessionCache = setting("sessionCache", Cache.Settings())
```

Setting names are global across the server and its modules; two settings with one name fail the build with
`ConflictingSettingsException`.

## Sharing one backend with the engine

Local engines (Ktor, Netty, JDK) declare their own `engine-cache` (schedule locks) and `engine-pubSub` (WebSocket
topics) settings.  `bind` points them at yours, so one URL configures both and the engine keys leave
`settings.json`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#shared-cache -->
```kotlin
object SharedCacheServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        // The engine's own "engine-cache" setting now resolves to this one and leaves settings.json.
        engineCache bind cache
    }
}
```

`enginePubSub bind pubSub` does the same for pub/sub (see [Pub/Sub](pubsub.md#websocket-topics-across-instances)).
`bind` works for any two settings with the same result type.

## Services in tests

`testBlocking`'s `settings` block runs before the runtime starts.  `set` replaces a setting's value; unset settings keep
their defaults:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#counter-test -->
```kotlin
fun counterTest() = CounterServer.testBlocking(settings = { cache set Cache.Settings("ram") }) {
    assertEquals(1L, increment.test(null, "hits"))
    assertEquals(2L, increment.test(null, "hits"))
}
```

Each `testBlocking` call builds a fresh runtime, so in-memory services start empty in every test.  Naming `"ram"`
explicitly keeps the test in memory even if the server's default later changes.

To inject a ready-made instance instead of a URL, use `setStatic`, which takes the setting's result type:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ServicesSamples.kt#counter-set-static -->
```kotlin
fun counterStaticTest() = CounterServer.testBlocking(settings = { context -> cache setStatic MapCache("test", context) }) {
    assertEquals(1L, increment.test(null, "hits"))
}
```

## Deploying

Service settings double as Terraform inputs: a deployment builder can create the backend and write its URL, e.g.
`cache.awsDynamoDb()`.  See [Terraform](terraform.md).

## Next

- [Settings](settings.md): the settings file, loading, secrets, and every framework setting.
- [Database](database.md), [Caching](caching.md), [Pub/Sub](pubsub.md), [Files](files.md), [Email](email.md):
  using each service.
