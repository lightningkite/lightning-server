# Architecture

How a Lightning Server works underneath: the definition you build, the engine that runs it, the executions every
piece of work runs as, and the small utilities they share.  Read this when you write framework-level code
(interceptors, modules, extensions) or need to know exactly what runs where.

## Imports

The samples on this page use these imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#architecture-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
```

## Two phases

A server has a **definition phase** and a **runtime phase**.

| | Definition | Runtime |
|---|---|---|
| Type | `ServerBuilder` (mutable) → `ServerDefinition` (immutable) | `Engine`, and a `ServerRuntime` per execution |
| When | Object initialization, then `build()` | After an engine is started with the definition and settings |
| Can do | Register endpoints, tasks, schedules, settings, interceptors, extensions | Read settings, use services, handle requests, launch tasks |
| Can't do | Read settings or touch services | Register anything |

This example uses both: the definition registers a task and an endpoint; at runtime the endpoint stores a value in
its execution's context and launches the task, which sees what launched it.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#execution-chain -->
```kotlin
@Serializable
data class TaskAuditRecord(val parent: Execution.ID?, val origin: Execution.ID, val tenant: String?)

val tenantKey = SerializableCache.Key<Execution, String>("my-app/tenant", String.serializer())

object TenantAuditServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    val audit = path.path("tasks").path("audit") bind Task { _: Unit ->
        val execution = serverRuntime.execution
        cache().set("last-audit", TaskAuditRecord(execution.parent, execution.origin, execution.context[tenantKey]))
    }

    val hello = path.get bind HttpHandler {
        serverRuntime.execution.context[tenantKey] = "acme"
        audit.launch(Unit)
        HttpResponse.plainText("Hello")
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#execution-chain-test -->
```kotlin
fun executionChainTest() = TenantAuditServer.testBlocking(settings = {}) {
    val requestId = Execution.ID.generate()
    TenantAuditServer.hello.test(requestId = requestId)

    val record = TenantAuditServer.cache().get<TaskAuditRecord>("last-audit")!!
    assertEquals(requestId, record.parent)
    assertEquals(requestId, record.origin)
    assertEquals("acme", record.tenant)
}
```

## The definition

A `ServerBuilder` is a set of registries.  Everything is registered with a DSL member:

| Member | Registers |
|---|---|
| `endpoint bind HttpHandler`, `path bind WebSocketHandler` | HTTP and WebSocket handlers |
| `path bind Task` / `StartupTask` / `PreDeployTask` / `ScheduledTask` | Background work ([Tasks](tasks.md), [Schedules](schedules.md)) |
| `path.topic(serializer)` | A WebSocket topic ([WebSockets](websockets.md)) |
| `setting(name, default)` | A setting, read at runtime by invoking it ([Settings](settings.md)) |
| `install(interceptor)` | An `ExecutionInterceptor`, `HttpInterceptor`, `WebSocketInterceptor` or `EncodingInterceptor` |
| `path include module` | Another `ServerBuilder`, mounted under `path` |
| `buildCheck { definition -> }` | A check run against the finished definition |
| `extensions[key] = value` | Arbitrary typed data ([Extensions](#extensions)) |

`build()` produces the `ServerDefinition` and runs every build check.  The definition flattens the included modules
into one tree with module paths prefixed, seals every registry, and rejects cyclic startup or pre-deploy task
dependencies.  Registering two handlers for the same method and path throws `DuplicateRegistrationException`.

Nothing in a definition can reach settings or services, so building is cheap and safe anywhere: in a test, in an SDK
generator, or in a Terraform emitter.

### Build checks

A `BuildCheck` validates an invariant that is only knowable once registration is finished.  A check registered in a
module still receives the whole server's definition, and modules' checks run first.  Throw to fail the build:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#build-check -->
```kotlin
object VersionedApi : ServerBuilder() {
    init {
        featureFlags.register("versioned-api")
        buildCheck { definition ->
            checkNotNull(definition.apiVersion) { "VersionedApi needs apiVersion set on the root server" }
        }
    }
}

object FlaggedServer : ServerBuilder() {
    init {
        apiVersion = "2"
        featureFlags.register("core")
    }

    val api = path.path("api") include VersionedApi
}
```

Including `VersionedApi` in a server that never sets `apiVersion` makes that server's `build()` throw.  A check has
no runtime: it can't read settings or touch services.  Checks are discarded after the build.

## Engine and ServerRuntime

An `Engine` is the running server, one per process.  It holds what is process-wide:

| Member | What it is |
|---|---|
| `server` | The `ServerDefinition` |
| `settings` | Loaded settings |
| `externalSerialization` / `internalSerialization` | Serialization for API bodies / for storage, caches and task queues |
| `clock` | The clock behind `now()`; tests can replace it |
| `publicUrl` | From the general settings |
| `serverId`, `serverVersion` | Identify this instance and build |
| `dispatchTask(task, input, from)` | How this engine runs a launched task |
| `sendWebSocketSubscriptionMessage(event)` | How this engine delivers topic messages |
| `webSocketHandlersRunOnSameMachine` | Whether all phases of one socket run in this process, so RAM can hold socket state |
| `processEngine` | The engine itself, even when reached through a runtime |

An engine on its own is doing no particular work.  A `ServerRuntime` is an engine while it performs one
**execution**; it carries that `execution`, and `history` lists the chain of executions it runs inside.  The
framework creates one per execution and every handler, interceptor and task body runs with it in context.  User
code never constructs one.

Which context to require:

- `context(ServerRuntime)` for code that does work: using services, launching tasks, sending to topics.  Most user
  code; handlers already run with one.
- `context(Engine)` for setup, and for helpers that only read shared state such as settings or serialization.

A `ServerRuntime` is an `Engine`, so engine-level code can be called from anywhere.  Get the runtime in context with
`serverRuntime`.

!!! warning
    A fresh runtime is created for every execution.  Anything cached at process scope must key on
    `processEngine`, not on the context object it was handed, or it misses and recomputes on every request.

### Engines

| Engine | Module | Runs tasks |
|---|---|---|
| `KtorEngine`, `NettyEngine`, `JdkEngine` (all `LocalEngine`s) | `engine-ktor`, `engine-netty`, `engine-jdk-server` | In a background coroutine in the same process |
| `AwsAdapter` | `engine-aws-serverless` | By invoking the Lambda asynchronously with the task, its input and the launching execution |
| `TestRunner` | `core` | Inline, before `launch` returns |

`TestRunner` is an `Engine`, not a `ServerRuntime`.  Handler `.test()` helpers run as real root executions; any
other work that needs a runtime goes inside `execute { }`.  See [Testing](testing.md) and [Engines](engines.md).

## Executions

An execution is one run of anything the server runs.  Each is an `Execution`:

| Kind | Started by | Extra fields |
|---|---|---|
| `Execution.Http` | An HTTP request, including a `/meta/bulk` part or an endpoint called from server code | `endpoint` (the concrete path requested) |
| `Execution.WebSocket` | One phase of a socket | `socketId`, `path`, `phase` (`Connect`, `Connected`, `ClientMessage`, `SubscriptionMessage`, `Disconnect`) |
| `Execution.Task` | A task launch | `location` |
| `Execution.Schedule` | A schedule tick | `location` |
| `Execution.Startup` | A startup task | `location` |
| `Execution.PreDeploy` | A pre-deploy task | `location` |
| `Execution.Direct` | Nothing on the server, such as a test's `execute { }` | |

Every execution records where it came from:

| Field | Meaning |
|---|---|
| `id` | This execution.  A UUID v7, so `id.timestamp()` says when it started |
| `parent` | The execution that started this one, or `null` |
| `rootExecution` | The head of the chain |
| `origin` | The execution the work is attributed to: the request for itself, the socket (`socketId`) for every phase, and for a task, the origin of whatever launched it |
| `context` | A `SerializableCache<Execution>` of values that flow down the chain |

`isRoot()` is `parent == null`: true for a request a client sent, a socket a client opened, a schedule tick, and
startup and pre-deploy tasks.  `logicalId` is `socketId` for socket phases and `id` for everything else.

A `/meta/bulk` part, a task launched from a handler, and a virtual socket inside a multiplexed one are children of
whatever started them.  Executions are serializable and deliberately small (no headers, no body, no principal), so
a task keeps its parent even when it runs in another process.  Their constructors are internal: user code reads
executions and never builds one.

### Execution context

`execution.context` carries values from an execution to everything it starts:

- A child gets a copy of its parent's context when it is created.  Later writes on either side aren't seen by the
  other.
- Executions with no parent start empty.
- A socket's phases don't share a context: each phase copies the context of whatever dispatched it.  State for the
  whole socket belongs on the connect request instead.
- The context is serialized with the execution, which is how it reaches a queued task.  Keep values small.  A key
  with `localOnly = true` is copied within the process but dropped when serialized.

### Invocation

An `ExecutionInterceptor` wraps every execution of every kind and receives an `Invocation`: the execution plus the
request that started it, when there is one.  The request is held in memory only.

| Invocation | Carries |
|---|---|
| `Invocation.Http` | `execution`, `request`, and `origin`: `Engine` (the engine received it), `Client` (a part of another request, such as `/meta/bulk`), or `Server` (server code called the endpoint) |
| `Invocation.WebSocket` | `execution`, the connect `request`, `virtual` |
| `Invocation.Unrequested` | `execution` of a task, schedule tick, startup or pre-deploy task, or direct execution |

`virtual` (on both requested kinds) is `true` for anything the engine did not receive itself.  `HttpInterceptor`
and `WebSocketInterceptor` see only the physical requests and sockets; the execution chain also sees virtual ones.
It wraps outside the other two: reject before calling `cont` and the error response still gets CORS and security
headers; throw after and it doesn't.  See [CORS & interceptors](cors-interceptors.md).

## Extensions

Extensions attach typed data to builders and definitions without changing their interfaces; the framework's own
`logger` property is one.  `ServerBuilder` is `Extendable` (it has
`MutableExtensions`); `ServerDefinition` is `Extended` (read-only `Extensions`).

A key is an object; a delegated property makes it discoverable:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#extension-key -->
```kotlin
object ApiVersionKey : MutableExtensions.Key<String>

var ServerBuilder.apiVersion: String? by ApiVersionKey
val ServerDefinition.apiVersion: String? by ApiVersionKey
```

A `WritableKey<WRITE, READ>` is mutable while building and read-only after: `default()` makes the builder's value,
`include` merges an included module's value into it, and `seal` produces the definition's value.
`ListRegistryExtension` and `MapRegistryExtension` implement it for lists and maps:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#writable-key -->
```kotlin
object FeatureFlagsKey : ListRegistryExtension<String>

val ServerBuilder.featureFlags: ListRegistry<String> by FeatureFlagsKey
val ServerDefinition.featureFlags: List<String> by FeatureFlagsKey
```

With the `FlaggedServer` from [Build checks](#build-checks):

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#extension-read -->
```kotlin
fun readExtensions() {
    val definition = FlaggedServer.build()
    assertEquals("2", definition.apiVersion)
    assertEquals(listOf("core", "versioned-api"), definition.featureFlags)
}
```

Merging when modules are included:

| Key type | Merge rule |
|---|---|
| `MutableExtensions.Key` | The including server's value wins; a module's value is used only if none is set |
| `MutableExtensions.SetOnceKey` | Setting it twice throws; `trySet` returns `false` instead |
| `MutableExtensions.WritableKey` | Values are combined with `include`, then `seal`ed at build |

Setting a `Key` to `null` removes it.  `getOrPut(key) { }`, `toMutableExtensions()` and `sealed()` are available on
extension maps.

## Data utilities

The `com.lightningkite.lightningserver.data` package.

### SerializableCache

A typed cache whose contents serialize, so it can travel with a request or an execution, including into a queued
task.  It is typed by its *scope*: a key made for `SerializableCache<Execution>` can't be used with a request's
cache.

| Where | Type |
|---|---|
| `execution.context` | `SerializableCache<Execution>` |
| `request.cache` (any `Request`, via `Caching`) | `SerializableCache<Request<*>>` |

`SerializableCache.Key(id, serializer, expireAfter, localOnly)` makes a plain key, as `tenantKey` above.  A
`CalculatingKey` computes its value on a miss; `Request.CacheKey<PATH, T>` is one whose input is the request
itself, so `request[key]` computes once per request:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ArchitectureSamples.kt#request-cache-key -->
```kotlin
object ClientKindKey : Request.CacheKey<PathSpec, String> {
    override val id = "my-app/client-kind"
    override val serializer = String.serializer()

    context(server: ServerRuntime)
    override suspend fun calculate(input: Request<PathSpec>): String {
        val agent = input.headers[HttpHeader.UserAgent]?.root ?: return "unknown"
        return if ("Mobile" in agent) "mobile" else "desktop"
    }
}

object ClientKindServer : ServerBuilder() {
    val kind = path.path("kind").get bind HttpHandler { request ->
        HttpResponse.plainText(request[ClientKindKey])
    }
}
```

This is how authentication is cached per request: `AuthCacheKey` in `auth` is a `CalculatingKey` over an
`Authentication`.

Reference:

- `cache[key]`, `cache[key] = value`, `getOrElse(key) { }` and `containsKey(key)` need an `Engine` in context (for
  serialization and the clock); `getOrPut(key) { }` and `cache.get(calculatingKey, input)` are `suspend`, and the
  latter needs a `ServerRuntime`.
- It is safe for concurrent use; concurrent `getOrPut`s or calculations of one key run the calculation once.
- Key ids must be unique: two different keys with one id throw `IllegalStateException` on retrieval.  Prefix them
  (`my-app/...`).
- `expireAfter` expires a value relative to the engine's clock.
- A `CalculatingKey` caches a calculated `null` unless `cachesNull` is `false`.

### Other utilities

| Type | Use |
|---|---|
| `Expiring<T>(value, expiresAt)` | A value with an optional expiry.  `Expiring(value, expireAfter = 5.minutes)` and `.expired` need an `Engine` in context for its clock |
| `LongBits` | A compact set of small integers (0 to 63) in a `Long`: `contains`, `lowestAfter`, `plus`, `intersect`, `size` |
| `CronPattern`, `CronDays`, `Schedule` | Schedule descriptions; `LocalDateTime + cronPattern` gives the next match.  See [Schedules](schedules.md) |
| `File.toKFile()`, `KFile.toJavaFile()` | Convert between `java.io.File` and Service Abstractions' multiplatform `KFile` |

## Shared types

`core-shared` is a Kotlin Multiplatform module with the types clients see.  Other modules follow the same split
(`typed-shared`, `sessions-shared`, `files-shared`, and so on); see [Project structure](project-structure.md).

### LSError

Every error response body:

| Field | Meaning |
|---|---|
| `http` | HTTP status code |
| `detail` | Machine-readable code (`"not-found"`); clients switch on this |
| `message` | Human-readable text |
| `data` | Extra structured data, as a JSON string |
| `stackTrace` | Only filled in when the general settings' `debug` is on |

The default exception handler turns an `HttpStatusException` (`BadRequestException`, `NotFoundException`, ...) into
an `LSError` with its status; any other exception becomes a 500 with `detail = "unknown"`, or the exception's class
name and message in debug.  Typed endpoints list theirs in `errorCases` for the docs and SDKs.  See
[Error handling](error-handling.md).

### HttpMethod

A value class with constants `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, `HEAD`, and the pseudo-method
`WEBSOCKET`, used internally for socket upgrades.

### MultiplexMessage

The frame format of `MultiplexWebSocketHandler`, which carries many virtual sockets over one connection:

| Field | Meaning |
|---|---|
| `channel` | Client-chosen id of the virtual socket |
| `path`, `queryParams` | Which socket to open; only on a start message |
| `start` | Client: open the channel.  The server replies with `start = true` once connected |
| `data` | A frame for the channel |
| `end` | Close the channel; the server sets `error` too when it closed because of a failure |
| `error` | Why the channel failed |

Each virtual socket's phases run as their own `Execution.WebSocket`s, children of the physical socket's.  See
[WebSockets](websockets.md).

## See also

- [Overview](overview.md): the building blocks from the top
- [Running](running.md) and [Engines](engines.md): starting a definition on an engine
- [Testing](testing.md): `testBlocking`, `execute { }`, handler `.test()`
- [CORS & interceptors](cors-interceptors.md): the three interceptor chains
- [Observability](observability.md): how executions show up in logs and traces
