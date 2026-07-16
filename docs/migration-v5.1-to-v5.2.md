# Migrating from v5.1 to v5.2

Last updated July 2026 (`5.2.0`)

Lightning Server 5.2 is a hardening and polish release on top of 5.1 — most of the diff is documentation,
tests, and internal robustness work, so there is no large-scale reorganization like the
[v4 → v5](migration-v4-to-v5.md) move. The changes below are the complete set: one dependency bump, two
source edits, and one settings-file edit — all small and mechanical.

!!! tip
    These four items were exactly what it took to move a real, mid-sized application (auth, sessions, files,
    AWS serverless deployment, a KiteUI web client) from 5.1 to 5.2 — nothing else was required, and the app
    ran and served its client unchanged afterward.

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

## Not source-breaking, but worth knowing

- **Engine reliability settings.** The JDK, Netty, and Ktor engines share a new `EngineReliabilitySettings`
  (graceful shutdown, bounded thread pools, request size/time limits). It has sensible defaults, so no code
  change is required; review the defaults if you tune server capacity.
- **Docs.** The endpoint, database, authentication, files, and websockets guides were substantially rewritten
  for 5.x — worth a re-read if you onboarded on an earlier prerelease.
