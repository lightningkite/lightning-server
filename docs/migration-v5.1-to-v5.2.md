# Migrating from v5.1 to v5.2

Last updated July 2026 (`5.2.0`)

Lightning Server 5.2 is a hardening and polish release on top of 5.1 — most of the diff is documentation,
tests, and internal robustness work, so there is no large-scale reorganization like the
[v4 → v5](migration-v4-to-v5.md) move. Sections 0–4 below are small, mechanical edits (one dependency bump,
two source edits, one settings-file edit). Section 5 is the one **runtime-behavior** change — pre-deploy
tasks — and whether it needs action depends on how you deploy.

!!! tip
    The first four items were exactly what it took to move a real, mid-sized application (auth, sessions,
    files, AWS serverless deployment, a KiteUI web client) from 5.1 to 5.2 with no behavior change. Section 5
    is separate: it only requires action if you run `serve` without a pre-deploy step (see that section).

## 0. Bump the service-abstractions version

Lightning Server 5.2 depends on a newer release of the `com.lightningkite.services:*` (service-abstractions)
library, and the telemetry refactor below spans both libraries. If your build pins a `serviceAbstractions`
version of its own, it **must** be raised to the version your Lightning Server 5.2 build was compiled against
— otherwise you get a clean compile but a runtime `NoSuchMethodError` (e.g.
`SettingContext.getOpenTelemetry()`) as mismatched classes meet on the classpath.

```toml
# gradle/libs.versions.toml
# Match whatever your Lightning Server 5.2 release depends on. 5.2.0 uses:
serviceAbstractions = "1.2.0-1-b2f7bd67"
```

You can confirm the exact version from the Lightning Server release's POM (the `com.lightningkite.services`
dependency versions). This is the single most important step — do it first.

## 1. `AuthEndpoints` / `SessionManager` now require a `cache`

`AuthEndpoints` (and its superclass `SessionManager`) gained a required `cache: Runtime<Cache>` constructor
parameter, positioned immediately after `database`. It backs auth-related caching (e.g. session/proof lookup
keys and rate limiting), so it must be a **shared** cache in any multi-instance or serverless deployment — not
the in-memory `"ram"` cache — otherwise instances will not see each other's entries.

```kotlin
// Before (5.1)
class SessionEndpoints : AuthEndpoints<User, User.ID>(
    principal = UserAuth,
    database = Server.database,
) { /* ... */ }

// After (5.2)
class SessionEndpoints : AuthEndpoints<User, User.ID>(
    principal = UserAuth,
    database = Server.database,
    cache = Server.cache,
) { /* ... */ }
```

Pass whatever `Runtime<Cache>` setting your server already defines (`Server.cache` in the example).

## 2. Telemetry settings: `OpenTelemetrySettings` → `TelemetryBackend.Settings`

Telemetry configuration was unified onto the service-abstractions `TelemetryBackend` SPI. Two things changed:

- The `telemetrySettings` global is now the **non-nullable** type
  `TelemetryBackend.Settings` (was a nullable `OpenTelemetrySettings?`).
- `com.lightningkite.services.otel.OpenTelemetrySettings` is deprecated. The same URL schemes it accepted are
  now registered directly on `TelemetryBackend.Settings` by the OpenTelemetry module.

Update the import:

```kotlin
// Before
import com.lightningkite.services.otel.OpenTelemetrySettings
// After
import com.lightningkite.services.telemetry.TelemetryBackend
```

Then translate the values. A configured backend becomes a `TelemetryBackend.Settings(url = ...)`, and
"telemetry off" — previously expressed as `null` — becomes the default `TelemetryBackend.Settings()`, whose
`url` defaults to `"noop"` (a no-op backend):

```kotlin
// Before (5.1)
telemetrySettings.direct(OpenTelemetrySettings("console", batching = null))
telemetrySettings.direct(null)   // disabled

// After (5.2)
telemetrySettings.direct(TelemetryBackend.Settings(url = "console"))
telemetrySettings.direct(TelemetryBackend.Settings())   // disabled (url = "noop")
```

The URL schemes `console`, `log`, `dev`, `debounced-dev`, and `otlp-grpc` / `otlp-http` / `otlp-https` are
registered when the OpenTelemetry module (`com.lightningkite.services:otel-jvm`) is on the classpath. Without
it, only the built-in `noop` scheme is available. Helper extensions such as
`telemetrySettings.otelGrafanaCloud(...)` are unaffected and continue to work.

## 3. Update the `telemetry` entry in existing `settings.json` files

Because `telemetrySettings` is no longer nullable (see above), the serialized form changed too. Any existing
`settings.json` (or `settings.testing.json`, etc.) that carries `"telemetry": null` — the 5.1 way of saying
"disabled" — will now fail to **load** at startup with a JSON parse error, since the deserializer expects a
`TelemetryBackend.Settings` object rather than `null`.

```jsonc
// Before (5.1)
"telemetry": null,

// After (5.2) — object form; url "noop" means disabled
"telemetry": { "url": "noop" },
```

A configured backend uses the same URL schemes as above, e.g. `"telemetry": { "url": "console" }`. This only
affects settings files you carry across the upgrade; a freshly generated settings file already uses the new
shape.

## 4. AWS serverless deployments must declare an `applicationVpc` (AWS deployers only)

If you deploy to AWS with `TerraformAwsServerlessDomainBuilder`, that builder now requires an `applicationVpc`
override — without one the deployment object fails to compile ("is not abstract and does not implement abstract
member: `val applicationVpc: AwsVpc`"). Deployments that don't run inside a VPC use `AwsVpc.None`:

```kotlin
object LkEnv : TerraformAwsServerlessDomainBuilder<Server>(Server) {
    // ...existing overrides (region, handler, storageBucket, ...)
    override val applicationVpc: AwsVpc = AwsVpc.None   // add this; import com.lightningkite.services.terraform.AwsVpc
}
```

This is compile-time only and unrelated to local runs, but every AWS deployment object in your project needs it.

## 5. Pre-deploy tasks: `serve` no longer prepares your database

This is the one **runtime-behavior** change to be aware of, and it matters most if you deploy with your
own pipeline rather than the Lightning Server AWS builders.

### What changed

Lightning Server gained a new concept, **`PreDeployTask`**, that sits alongside `StartupTask`:

- A **`StartupTask`** runs in *every server instance* as it boots, on the request-serving path.
- A **`PreDeployTask`** runs *once per deploy*, in a dedicated `predeploy` invocation, **before the new
  version starts serving** and concurrently with the still-live previous version. If it fails, the deploy
  is aborted and the old version keeps serving.

Database table/index reconciliation — the work `ModelInfo` used to register as a `StartupTask` (its
`prepare` step) — is now a **`PreDeployTask`**. This moves migration work off every cold start and
scale-out (a real latency win, especially on Lambda) and guarantees it completes before new code serves.

!!! warning "The breaking part"
    Because table preparation moved out of startup, **`bin/server serve` no longer prepares your
    database.** Anywhere you previously relied on a plain `serve` to create tables/indexes — local
    development against a real database, or a custom single-process production deploy — must now run the
    pre-deploy step. (Unit tests are unaffected: the test harness runs neither startup nor pre-deploy
    tasks automatically.)

### What you need to do

**1. Expose a `predeploy` command** in your application's CLI, mirroring your existing `serve` command.
Each engine (Ktor/Netty/JDK) exposes `runPreDeploy()`, which loads settings, runs all pre-deploy tasks
fail-fast, disconnects services, and returns (non-zero exit on failure):

```kotlin
private fun predeploy() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        runPreDeploy()   // runs all PreDeployTasks once, then returns
    }
}

fun main(vararg args: String) {
    cli(arguments = args, available = listOf(::serve, ::predeploy, /* ... */))
}
```

**2. For local development**, add a convenience `dev` command that does prepare-then-serve in one process
(so a single command still "just works"):

```kotlin
private fun dev() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        settings.ready()
        runPreDeployTasksBlocking()   // prepare, leaving services connected
        start(Netty)                  // then serve
    }
}
```

**3. In production, run `predeploy` before cutover.** If you deploy with the Lightning Server AWS
builders, **this is already wired for you** — the EC2 (single and scaling) and serverless deployments now
run the pre-deploy step before switching traffic to the new version, and abort the deploy if it fails. If
you roll your own pipeline, invoke `bin/server predeploy` and gate the cutover on its success.

### Migrating your own `StartupTask`s

Audit each `StartupTask` you defined and decide where it belongs:

- **Move to `PreDeployTask`** — anything that mutates shared state the new code depends on: schema/index
  creation, data backfills, one-time setup, and prep that must be done before new code serves.
- **Keep as `StartupTask`** — genuinely per-instance work: in-memory cache warming, establishing
  instance-local state, cheap config validation.

`PreDeployTask` uses lazily-supplied dependencies (so you can reference tasks declared later or in other
modules without initialization-order surprises), and **every pre-deploy task runs on every deploy** — the
framework tracks no history, so make them idempotent/convergent. For genuinely "run exactly once ever"
work, guard it with a database marker (`doOnce`) *inside* a `PreDeployTask`; because pre-deploy runs once
per deploy off the serving path, that check is cheap and uncontended:

```kotlin
// Before (5.1): a one-time seed as a per-instance startup task
val setupAdmins = path.path("setup-admins") bind startupOnce(database) {
    userInfo.table().insertOne(User(email = "admin@example.com", isSuperUser = true))
}

// After (5.2): a pre-deploy task; doOnce keeps it once-ever
val setupAdmins = path.path("setup-admins") bind PreDeployTask {
    doOnce("setup-admins", database) {
        userInfo.table().insertOne(User(email = "admin@example.com", isSuperUser = true))
    }
}
```

!!! note "Startup tasks now fail fast"
    A related fix: a failing `StartupTask` now aborts startup. Previously an exception from a startup task
    with no dependents was silently swallowed and the server started anyway. If any of your startup tasks
    threw and you relied on that being ignored, make the error handling explicit.

## Not source-breaking, but worth knowing

- **Engine reliability settings.** The JDK, Netty, and Ktor engines share a new `EngineReliabilitySettings`
  (graceful shutdown, bounded thread pools, request size/time limits). It has sensible defaults, so no code
  change is required; review the defaults if you tune server capacity.
- **Docs.** The endpoint, database, authentication, files, and websockets guides were substantially rewritten
  for 5.x — worth a re-read if you onboarded on an earlier prerelease.
