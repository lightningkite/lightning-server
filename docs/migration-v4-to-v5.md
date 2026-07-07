# Migrating from v4 to v5

Last updated July 2025 (`version-5`)

Lightning Server 5 is a substantial reorganization of version 4. The biggest change is that the service
abstractions (database, cache, files, email, notifications) were extracted into a separate
`com.lightningkite.services.*` library, and the server itself was split into a **definition** phase and a
**runtime** phase. This guide covers the server-side changes.

!!! note
    The authoritative, continually-updated migration reference — including the KiteUI 7 client-side changes — is
    the `ls5-kui7-migration` skill. This page summarizes the server portions; consult the skill for the full
    checklist and for client/app migration.

## Overview of major changes

- **Service abstractions extracted.** Database, files, cache, email, and notifications now live under
  `com.lightningkite.services.*` instead of `com.lightningkite.lightningserver.*`.
- **Definition vs. runtime split.** A server is now declared as a `ServerBuilder` (the definition). Services and
  settings resolve to `Runtime<T>` values that are only realized inside a `ServerRuntime` context.
- **Context parameters.** Kotlin context parameters are used heavily — most runtime code carries
  `context(runtime: ServerRuntime)`. This requires the `-Xcontext-parameters` compiler flag.
- **Standard-library types.** `com.lightningkite.UUID` → `kotlin.uuid.Uuid`, and `kotlinx.datetime.Instant` →
  `kotlin.time.Instant`.
- **Module split.** The single `...Shared` client artifact was split into `core-shared`, `typed-shared`,
  `sessions-shared`, `files-shared`, and `media-shared`.

## Dependencies

The old single server and single shared dependencies become several targeted modules, and database/files/etc.
come from the service-abstractions library. Representative server dependencies:

```kotlin
dependencies {
    api(libs.comLightningKite.services.database)
    api(libs.comLightningKite.services.database.jsonfile)
    api(libs.comLightningKite.lightningServer.core)
    api(libs.comLightningKite.lightningServer.typed)
    api(libs.comLightningKite.lightningServer.files)
    api(libs.comLightningKite.lightningServer.sessions)
    ksp(libs.comLightningKite.services.database.processor)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
```

The database KSP processor moved from `...lightningserver...Processor` to
`com.lightningkite.services:database-processor`. See the migration skill for the full artifact-name mapping.

## Imports

The most common import moves:

```kotlin
// Standard-library types
import kotlin.uuid.Uuid                                   // was com.lightningkite.UUID
import kotlin.time.Instant                                // was kotlinx.datetime.Instant
import com.lightningkite.lightningserver.runtime.now      // was com.lightningkite.now

// Database & data (was com.lightningkite.lightningdb.*)
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*

// Services (were under com.lightningkite.lightningserver.*)
import com.lightningkite.services.files.ServerFile        // was ...lightningserver.files.ServerFile
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.database.Database
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.email.Email
import com.lightningkite.services.notifications.NotificationService

// Server builder (was com.lightningkite.lightningserver.core.ServerPathGroup)
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
```

## Server definition: `ServerPathGroup` → `ServerBuilder`

Servers and endpoint groups are now `ServerBuilder` objects. Settings use the service `Settings()` factories, and
sub-groups are attached with `module`/`include`:

```kotlin
// OLD
object Server : ServerPathGroup(ServerPath.root) {
    val database = setting(name = "database", default = DatabaseSettings())
    val files = setting(name = "files", default = FilesSettings())
    val users = UserEndpoints(path("users"))
}

// NEW
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", PublicFileSystem.Settings())
    val users = path.path("users") module UserEndpoints
}
```

Settings changed from standalone `*Settings()` constructors to nested `Service.Settings()` factories, for example
`CacheSettings()` → `Cache.Settings()`, `FilesSettings()` → `PublicFileSystem.Settings()`,
`NotificationSettings("console")` → `NotificationService.Settings("console")`.

## Definition vs. runtime

In v4, calling a setting gave you the service directly. In v5 a setting is a `Runtime<T>` in the definition, and
you resolve it inside a `ServerRuntime` context (for example within a handler, where the context is present). Code
that touches services must therefore carry `context(runtime: ServerRuntime)`:

```kotlin
// A helper that uses the database now declares the runtime context.
context(runtime: ServerRuntime)
suspend fun countUsers(): Int = database().table<User>().count()
```

This is the most pervasive source of migration compile errors: functions called from handlers, hooks, or signals
need the `context(runtime: ServerRuntime)` receiver added.

## Endpoint registration

Handlers and sub-groups are attached to paths with infix operators instead of constructor-with-path:

```kotlin
// Sub-group of endpoints
val users = path.path("users") module UserEndpoints

// A single ServerBuilder component (e.g. generated REST endpoints)
val rest = path.path("rest") include ModelRestEndpoints(info)

// A single handler / task / topic
val root = path.get bind HttpHandler { HttpResponse.plainText("Hello") }
```

## Database changes

- `.collection()` → `.table()`, and `.baseCollection()` → `.baseTable()`.
- `UUID` → `Uuid` throughout models: `HasId<UUID>` → `HasId<Uuid>`, `UUID.random()` → `Uuid.random()`.
- Instants use `kotlin.time.Instant`; use `now()` from `com.lightningkite.lightningserver.runtime.now`.
- `ModelInfo` signals operate on tables and take context-parameterized hooks.

```kotlin
// OLD
info.collection().insertOne(model)

// NEW
info.table().insertOne(model)
```

## Typed endpoints

Typed endpoints are declared with `ApiHttpHandler` bound to a path, taking the auth requirement as `auth` and the
logic as `implementation`:

```kotlin
val hello = path.path("hello").get bind ApiHttpHandler(
    summary = "Hello",
    description = "Returns a greeting",
    auth = noAuth,
    implementation = { input: Unit -> "Hello" }
)
```

See [Typed Endpoints](typed-endpoints.md) for the full API.

## WebSockets

The websocket package moved from `...websocket` (singular) to `...websockets` (plural), and handlers are attached
with `bind`/`include`:

```kotlin
// OLD
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandler
val multiplex = path("multiplex").websocket(MultiplexWebSocketHandler(cache))

// NEW
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()
```

See [WebSockets](websockets.md) for the current API.

## Common pitfalls

- **Missing context parameter.** "Missing context receiver" compile errors mean a function that uses services
  needs `context(runtime: ServerRuntime)`.
- **`collection()` unresolved.** Replace `.collection()`/`.baseCollection()` with `.table()`/`.baseTable()`.
- **Wrong `Instant` import.** Use `kotlin.time.Instant`, not `kotlinx.datetime.Instant`.
- **Duplicate `UploadEarlyEndpoint`.** Instantiate it exactly once; multiple instances register conflicting
  `ServerFile` serializers and cause runtime 500s. See [File Systems](files.md).

## Client and KiteUI migration

This page intentionally focuses on the server. The client SDK and KiteUI 7 changes (dependency renames, the dash →
dot view-modifier syntax, theme derivations, `Api2` → `Api`, `OtpSecret` → `TotpSecret`, and more) are extensive
and are documented in full in the `ls5-kui7-migration` skill.
