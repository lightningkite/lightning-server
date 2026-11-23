# Overview

Lightning Server is a Kotlin framework for HTTP and WebSocket APIs.  You declare the whole server (endpoints,
background tasks, schedules, and the services it needs) as a Kotlin object; an engine runs it.  The same declaration
runs in a unit test, on a port, or on AWS Lambda.

## A server and its test

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-server -->
```kotlin
object OverviewServer : ServerBuilder() {
    val greeting = setting("greeting", "Hello")

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("${greeting()}, world!")
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-server-test -->
```kotlin
fun overviewServerTest() = OverviewServer.testBlocking(settings = { greeting set "Howdy" }) {
    val response = OverviewServer.root.test()
    check(response.body?.text() == "Howdy, world!")
}
```

`setting("greeting", "Hello")` declares a value read from `settings.json` at runtime, with a default.  The test
overrides it, runs entirely in memory, and binds no port.

## Definition and runtime

```
ServerBuilder ──build()──► ServerDefinition ──► engine
 endpoints                                       TestRunner      (testBlocking {}, in core)
 tasks, schedules                                KtorEngine      (engine-ktor)
 settings                                        NettyEngine     (engine-netty)
 interceptors                                    JdkEngine       (engine-jdk-server)
                                                 AwsAdapter      (engine-aws-serverless)
```

Declaring a `ServerBuilder` runs nothing.  `build()` collects every declaration into a `ServerDefinition`, and an
engine loads settings, connects services, and executes it.  Settings decide which backend each service uses (`ram`
in tests, MongoDB or Redis in production), so moving between environments changes configuration, not code.  See
[Architecture](architecture.md) for the details.

## Context parameters

Handler bodies, task bodies, and `test {}` blocks run with a runtime in scope as a Kotlin context parameter.  That
is why `greeting()` above, and service calls like `database()` or `auth.fetch()`, need no argument.  Functions you
write that call them declare the same context:

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-context -->
```kotlin
context(engine: Engine)
fun shout(): String = OverviewServer.greeting().uppercase()
```

`ServerRuntime` is one execution (a request, a task run) and knows what started it; `Engine` is the process-wide
server (settings, serialization, task dispatch).  Every `ServerRuntime` is an `Engine`.  Take a `ServerRuntime` for
work done on someone's behalf, an `Engine` for work that isn't (boot, settings, SDK generation).  Calling one of these
functions with neither in scope, such as from an object initializer, is a compile error: move the call into a
handler, task, or test.

## Building blocks

| Piece | What it is | Chapter |
|---|---|---|
| HTTP endpoint | Path + method + handler, `path.get bind HttpHandler { ... }` | [Your first endpoint](first-endpoint.md), [Routing](routing.md) |
| Typed endpoint | `ApiHttpHandler`: JSON in and out, OpenAPI, generated client SDKs | [Typed endpoints](typed-endpoints.md), [Client SDKs](client-sdks.md) |
| WebSocket | A handler on a path that receives and sends frames | [WebSockets](websockets.md) |
| Setting / service | A named value or service (database, cache, email, files) resolved from `settings.json` | [Services](services.md), [Settings](settings.md) |
| Database | Service Abstractions' `Database`, with a type-safe query DSL for `@Queryable` models | [Database](database.md) |
| Model REST | CRUD, query, and bulk endpoints for a table, with permissions | [Model REST](model-rest.md), [Permissions](permissions.md) |
| Authentication | `PrincipalType` ties a model to bearer tokens; endpoints declare `auth = UserAuth.require()` | [Authentication](auth.md) |
| Sessions | Login by accumulating proofs (email or SMS PIN, password, TOTP, passkey, OAuth) | [Proof sessions](proof-session.md) |
| Task | Background work with a serializable input, started with `task.launch(input)` | [Tasks](tasks.md) |
| Schedule | Periodic work: `Schedule.Frequency`, `Schedule.Daily`, `Schedule.Cron` | [Schedules](schedules.md) |
| Engine | What runs the definition | [Running](running.md), [Engines](engines.md) |

## Next

- [Setup](setup.md): add Lightning Server to a Gradle project
- [Your first endpoint](first-endpoint.md)
