# Bulk requests and funnels

Two ready-made endpoint groups from the `typed` module: `/meta/bulk`, which runs many API calls in one HTTP request,
and `FunnelEndpoints`, which tracks how users get through multi-step flows.

## Bulk requests

`MetaEndpoints` includes `POST bulk`.  A client sends a map of named calls; the server runs them in parallel through the
normal routing and returns a map of named results.  Use it to load a screen's worth of data in one round trip.

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#bulk-server -->
```kotlin
object BulkServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val ping = path.path("ping").get bind ApiHttpHandler(
        summary = "Ping",
        auth = noAuth,
        implementation = { _: Unit -> "pong" }
    )

    val missing = path.path("missing").get bind ApiHttpHandler<_, _, Unit, String>(
        summary = "Missing",
        auth = noAuth,
        implementation = { _: Unit -> throw NotFoundException("not here") }
    )

    // POST /meta/bulk, among the other meta endpoints
    val meta = path.path("meta") include MetaEndpoints(
        packageName = "com.example.api",
        cache = cache,
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#bulk-test -->
```kotlin
fun bulkTest() = BulkServer.testBlocking(settings = {}) {
    val results = BulkServer.meta.bulk.test(
        null,
        mapOf(
            "ping" to BulkRequest(path = "/ping", method = "GET"),
            "gone" to BulkRequest(path = "/missing", method = "GET"),
        )
    )

    check(results.getValue("ping").result == "\"pong\"")
    check(results.getValue("gone").error?.http == 404)
}
```

Imports for these examples:

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#bulk-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
```

`MetaEndpoints` also serves health, OpenAPI, and the docs UI; see [Meta and admin](meta-admin.md).

### Wire format

Request:

```json
{
  "profile": { "path": "/users/self", "method": "GET" },
  "recent":  { "path": "/posts?limit=5", "method": "GET" },
  "draft":   { "path": "/posts", "method": "POST", "body": "{\"title\":\"Hello\"}" }
}
```

| `BulkRequest` field | Type | Notes |
|---|---|---|
| `path` | `String` | Endpoint path, with an optional `?query` |
| `method` | `String` | `GET`, `POST`, ... |
| `body` | `String?` | The body, as JSON text |

Response, with the same keys:

```json
{
  "profile": { "result": "{\"_id\":\"...\",\"email\":\"ann@example.com\"}", "durationMs": 4 },
  "recent":  { "result": "[...]", "durationMs": 12 },
  "draft":   { "error": { "http": 400, "detail": "validation-failed", "message": "...", "data": "..." }, "durationMs": 1 }
}
```

| `BulkResponse` field | Type | Notes |
|---|---|---|
| `result` | `String?` | The part's response body as JSON text, when it succeeded.  `null` for a part with no body |
| `error` | `LSError?` | The part's error, when it failed |
| `durationMs` | `Long` | Time the part took |

### Behavior

- The bulk call itself returns 200 even when parts fail; each part's failure is in its `error`.
- Each part carries the outer request's headers, including `Authorization`, and is checked against its own endpoint's
  auth requirement.  All parts run as the same caller.
- Parts run concurrently and independently.  Nothing is transactional: a failed part doesn't undo the others.
- Each part is its own execution, so access logs, audit, and traces show one entry per part, named after its route,
  under the bulk request's span.
- Rate limiting charges each part.  Pass `meta.bulk` in the `RateLimitInterceptor`'s `exempt` list, or the wrapper is
  charged as well; see [Rate limiting](rate-limiting.md).
- Bodies are always treated as JSON.

## Funnels

`FunnelEndpoints` records each attempt at a multi-step flow (onboarding, checkout) and summarizes them daily.  The
client starts an instance, reports steps and errors, and marks success.

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#funnel-server -->
```kotlin
object FunnelServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    // Who may read summaries and instances.  Recording steps is open to everyone.
    val funnels = path.path("funnels") include FunnelEndpoints(database, read = AuthRequirement.IsAdmin)
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#funnel-test -->
```kotlin
fun funnelTest() = FunnelServer.testBlocking(settings = {}) {
    val id = FunnelServer.funnels.start.test(
        null,
        FunnelStart(funnel = "checkout", userAgent = "test", version = "1.0.0"),
    )
    FunnelServer.funnels.step.test(id, null, 1)
    FunnelServer.funnels.success.test(id, null, Unit)
}
```

Endpoints, relative to the mount point:

| Endpoint | Input | Auth |
|---|---|---|
| `POST start` | `FunnelStart`; returns the instance's `Uuid` | anyone |
| `POST step/{id}` | `Int`, the step reached | anyone |
| `POST error/{id}` | `String`, a short error description | anyone |
| `POST success/{id}` | none | anyone |
| `POST summarize-now` | `LocalDate?` (today when `null`) | `read` |
| `GET summaries/{date}` | none; returns that day's `FunnelSummary`s | `read` |
| `summary/rest`, `instance/rest` | [Model REST](model-rest.md) endpoints for `FunnelSummary` and `FunnelInstance` | `read` |

`FunnelStart` fields: `funnel` (the flow's name), `userAgent`, `version`, `expireAfterMinutes` (default 20), and
`expectedErrorRate` (default 0.05).

A schedule runs at 08:00 in the zone given to `FunnelEndpoints` (default `America/Denver`) and writes one
`FunnelSummary` per funnel for the instances started in the previous 24 hours (08:00 to 08:00).  It holds the fractions
that succeeded, succeeded after an error, errored without succeeding, and neither errored nor succeeded (abandoned), the
instance count, and a `status`: `OK` when the error fraction is under half of `expectedErrorRate`, `WARNING` when it is
under `expectedErrorRate`, `ERROR` otherwise.  Re-running a day replaces its summaries.

## Next

- [Meta and admin](meta-admin.md): the rest of `MetaEndpoints`.
- [Client SDKs](client-sdks.md): the clients that call these endpoints.
- [Schedules](schedules.md): how the daily summary is scheduled.
