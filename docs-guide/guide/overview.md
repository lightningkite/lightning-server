# What is a Lightning Server Made Of?

Lightning Server is a Kotlin framework for building production-ready HTTP APIs. You declare
your server as a plain Kotlin object; an **engine** runs it.  Define once, deploy anywhere —
the same definition compiles to Ktor (local dev), Netty, JDK, or AWS Lambda.

---

## The mental model

```
ServerBuilder (definition)  ──► ServerDefinition ──► Engine (runs it)
     │
     ├─ endpoints (HTTP, WebSocket)
     ├─ tasks (background work)
     ├─ schedules (cron / periodic work)
     └─ settings (injected services: database, cache, email, …)
```

A `ServerBuilder` is a **declarative definition**.  Nothing runs when you declare it — calling
`.build()` on it produces a `ServerDefinition`, which an engine then executes.  Because the
definition is data, the same object can be:

- started as an HTTP server (`KtorEngine`, `NettyEngine`, `JdkServerEngine`)
- run in a unit test (`LocalEngine` via the `.test {}` helper — no ports, no infrastructure)
- deployed to AWS Lambda (the `AwsAdapter` reads it and generates Terraform)

---

## A note on Kotlin context parameters

Throughout Lightning Server's API you will see a `context(server: ServerRuntime)` (or
`context(settings: SettingContext)`) annotation on functions:

```kotlin
context(server: ServerRuntime)
override suspend fun requiredProofStrengthFor(subject: User): Int = 5
```

These are **Kotlin context parameters** — the compiler requires that a value of the named type
is in scope at the call site, but you never pass it explicitly.  The framework supplies the
`ServerRuntime` automatically whenever your code runs inside a handler body, a task body, a
scheduled task body, or a `.test {}` block.  That is why you can call `cache()`, `database()`,
`auth.fetch()`, and similar service accessors anywhere in those contexts without threading a
parameter through your code.

If you call a context-requiring function outside of one of these provided contexts — e.g. during
object initialization — the compiler will tell you a required context is missing.  The fix is
always to move the call inside a handler, task, test, or other framework-managed scope.

---

## The smallest possible server

These imports and this server are the verified sample this guide will build on:

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import kotlinx.coroutines.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-server -->
```kotlin
// A complete Lightning Server in ~5 lines: declare a ServerBuilder, add an endpoint.
object OverviewServer : ServerBuilder() {

    // GET / — responds "Hello, world!"
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, world!")
    }
}
```

And its test:

<!-- sample: com/lightningkite/lightningserver/guide/samples/OverviewSamples.kt#overview-server-test -->
```kotlin
fun overviewServerTest() = runBlocking {
    OverviewServer.test(settings = {}) {
        val response = OverviewServer.root.test()
        check(response.body?.text() == "Hello, world!")
    }
}
```

That is a **complete, tested Lightning Server**.  The chapters below add features on top of this
foundation.

---

## Building blocks

### HTTP Endpoints

An endpoint is a `path + HTTP method + handler` bound together with the `bind` infix.  Untyped
`HttpHandler` gives you full control of the request/response; typed `ApiHttpHandler` (from the
`typed` module) adds automatic JSON serialization, OpenAPI documentation, and SDK generation.

See: [Your First Endpoint](first-endpoint.md) · [Routing](routing.md) · [Typed Endpoints](typed-endpoints.md)

### WebSockets

A WebSocket endpoint lives on a path just like an HTTP endpoint, but its handler receives a
`WebSocketSession` that you read from and send frames to.  The `typed` module adds
`ApiWebsocketHandler` for typed pub/sub topics.

See: [WebSockets](websockets.md)

### Tasks

A `Task` is a named unit of background work with a serializable input type.  You call
`task.launch(input)` to enqueue it; the engine executes it asynchronously (or synchronously in
tests).  Tasks decouple long-running work from the request that triggered it.

See: [Tasks](tasks.md)

### Schedules

A `ScheduledTask` runs on a `Schedule` — either a fixed frequency (`Schedule.Frequency`), a
daily time (`Schedule.Daily`), or a cron expression (`Schedule.Cron`).  The engine handles
distributed locking so only one instance runs per tick in a multi-replica deployment.

See: [Schedules](schedules.md)

### Services & Settings

A `setting(...)` call declares a service dependency (database, cache, email, SMS, files, …)
that is resolved at runtime from `settings.json`.  In tests you override it with the `settings`
lambda.  Swapping implementations (e.g. RAM → MongoDB) requires only a settings change — no
code change.

See: [Services & Settings](services.md)

### Authentication

`PrincipalType<SUBJECT, ID>` is the bridge between a model and the auth system.  Endpoints
declare `authOptions = UserAuth.require()` to require a bearer token; handlers access the
authenticated subject via `auth.fetch()`.

See: [Authentication & Sessions](auth.md)

### Proof & Session

Users log in by accumulating **proofs** — signed, time-limited assertions (email PIN, SMS PIN,
password, TOTP, WebAuthn, …).  Once the total proof strength meets a per-user threshold,
`AuthEndpoints` issues a session and returns a bearer token pair.

See: [Proof & Session Authentication](proof-session.md)

### Database & Query DSL

`Database` is a service abstraction over MongoDB, Postgres, and in-memory / JSON-file backends.
Models annotated with `@GenerateDataClassPaths` get a compile-time query DSL — `condition {}`,
`modification {}` — that prevents typos and field-rename drift.

See: [Database](database.md)

### Running Your Server

`ServerBuilder.build()` produces a `ServerDefinition`.  Pass it to an engine (`KtorEngine`,
`NettyEngine`, or `JdkServerEngine`), load `settings.json` with `.loadFromFile(...)`, and call
`.start(...)`.  On first run the framework writes a default `settings.json`; the second run uses
it and starts normally.

See: [Running Your Server](running.md)

### Deploying to AWS

`AwsAdapter` reads your `ServerDefinition` and generates Terraform that provisions API Gateway,
Lambda, DynamoDB, S3, and Secrets Manager — all derived from your declared settings.

See: [AWS Deployment](aws-deployment.md)

---

## Value propositions

- **End-to-end type safety** — from the Kotlin handler signature to the auto-generated
  TypeScript / Kotlin SDK.  Rename a field; the SDK and the handler both break at compile time.
- **Define once, run anywhere** — the same `ServerBuilder` runs in a JUnit test (no ports, no
  infrastructure), in Ktor for local dev, in Netty/JDK for production, and in AWS Lambda for
  serverless.  No adapters to write.
- **Services swappable via settings** — swap RAM cache for Redis, local files for S3, or
  JSON-file DB for MongoDB by changing one line in `settings.json`.  Test code stays unchanged.
- **Infrastructure from definition** — the AWS engine generates Terraform from your declared
  settings.  Add a `setting("files", Files.Settings())` and the S3 bucket appears in the
  generated plan.

---

## Glossary

| Term | Meaning |
|---|---|
| `ServerBuilder` | A Kotlin `object` subclass where you declare endpoints, tasks, schedules, and settings |
| `ServerDefinition` | The result of calling `.build()` on a `ServerBuilder` — a data structure the engine executes |
| Endpoint | A path + HTTP method + handler triple, declared with `path.get bind HttpHandler { … }` |
| Handler | The function that runs when a request arrives — `HttpHandler` (raw) or `ApiHttpHandler` (typed) |
| Engine | The runtime that executes a `ServerDefinition` — `LocalEngine` (tests), `KtorEngine`, `NettyEngine`, `JdkServerEngine`, `AwsAdapter` |
| Setting | A named, lazily-resolved service dependency declared with `setting("name", Default)` |
| Service | An abstraction over infrastructure — `Database`, `Cache`, `Files`, `Email`, `Sms` |
| Principal | The model type representing an authenticated entity (e.g. `User`) |
| Proof | A signed, time-limited assertion that a user has verified an identity property |
| Session | A server-side record created when a user's accumulated proofs meet the strength threshold |
| Task | A named unit of background work with a serializable input, invoked via `task.launch(input)` |
| Schedule | A periodic trigger — `Schedule.Frequency`, `Schedule.Daily`, or `Schedule.Cron` |
