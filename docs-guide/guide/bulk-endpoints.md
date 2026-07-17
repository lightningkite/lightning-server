# Bulk Requests & Funnel Analytics

This chapter covers two distinct features that both live in the `:typed` module:

- **Bulk requests** (`MetaEndpoints.bulk`) — send many typed API calls in a single HTTP
  request and receive all results in one response.
- **Funnel analytics** (`FunnelEndpoints`) — track user journeys through multi-step flows
  and surface daily health summaries.

---

## Part 1: Bulk Requests

### What it is

`MetaEndpoints` exposes a `POST /meta/bulk` endpoint that accepts a map of named
sub-requests, fans them out to the real endpoint handlers **in parallel**, and returns a
map of named results.  The outer request always returns HTTP 200 — per-sub-request errors
are captured in the response body, not surfaced as top-level HTTP status codes.

This is useful for:

- Mobile or web clients that need several independent API responses to render a single
  screen, and want to reduce round-trips.
- Batch operations where each call is logically independent (not a transaction).
- Dashboard pages that aggregate data from several endpoints at once.

### Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#bulk-imports -->
```kotlin
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import kotlinx.serialization.json.Json
```

### Mounting the bulk endpoint

The bulk endpoint lives inside `MetaEndpoints`.  Mount it with `include` at whatever
path prefix you prefer (conventionally `/meta`):

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#bulk-server -->
```kotlin
object BulkServer : ServerBuilder() {
    init {
        // registerBasicMediaTypeCoders() enables JSON body parsing that the bulk endpoint
        // needs to deserialise the incoming Map<String, BulkRequest> and serialise the
        // Map<String, BulkResponse> it returns.
        registerBasicMediaTypeCoders()
    }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    // GET /ping — always succeeds; used to verify a successful sub-request in tests.
    val ping = path.path("ping").get bind ApiHttpHandler(
        summary = "Ping",
        auth = noAuth,
        implementation = { _: Unit -> "pong" }
    )

    // GET /missing — always throws; used to verify a failed sub-request in tests.
    val missing = path.path("missing").get bind ApiHttpHandler<_, _, Unit, String>(
        summary = "Missing",
        auth = noAuth,
        implementation = { _: Unit -> throw NotFoundException("not here") }
    )

    // POST /meta/bulk — accepts Map<String, BulkRequest>, fans out in parallel,
    // returns Map<String, BulkResponse>.  Always HTTP 200; per-sub-request errors
    // land in the body rather than as top-level HTTP status codes.
    val meta = path.path("meta") include MetaEndpoints(
        packageName = "com.example.guide",
        database = database,
        cache = cache,
    )
}
```

`MetaEndpoints` also adds `/meta/health`, `/meta/openapi`, `/meta/docs`, and other
diagnostic endpoints.  See [Typed Endpoints](typed-endpoints.md) for the complete list.

`registerBasicMediaTypeCoders()` is **required** in the `init {}` block when you mount
`MetaEndpoints`; without it the framework cannot parse the JSON body of the bulk request
or serialise the response map.

### Request and response shape

`POST /meta/bulk` accepts a JSON object.  The keys are caller-chosen names; the values
are `BulkRequest` objects.

```json
// Illustrative — JSON request bodies are not drift-checked.
{
  "userProfile": { "path": "/users/me", "method": "GET" },
  "recentPosts":  { "path": "/posts?limit=5", "method": "GET" },
  "createDraft":  {
    "path": "/posts/draft",
    "method": "POST",
    "body": "{\"title\":\"Hello\",\"body\":\"World\"}"
  }
}
```

`BulkRequest` fields:

| Field    | Type      | Required | Description |
|----------|-----------|----------|-------------|
| `path`   | `String`  | yes      | Endpoint path, optionally with a `?query=string` suffix |
| `method` | `String`  | yes      | HTTP method (`"GET"`, `"POST"`, etc.) |
| `body`   | `String?` | no       | JSON-encoded request body; omit for GET/DELETE |

The response mirrors the request's keys:

```json
// Illustrative.
{
  "userProfile": { "result": "{\"_id\":\"abc\",\"email\":\"user@example.com\"}", "durationMs": 4 },
  "recentPosts":  { "result": "[{\"title\":\"First post\"}]", "durationMs": 12 },
  "createDraft":  {
    "error": { "http": 400, "detail": "validation-failed", "message": "Title must not be blank" },
    "durationMs": 1
  }
}
```

`BulkResponse` fields:

| Field        | Type      | Description |
|--------------|-----------|-------------|
| `result`     | `String?` | JSON-encoded response body on success |
| `error`      | `LSError?`| Structured error if the sub-request failed |
| `durationMs` | `Long`    | Milliseconds the sub-handler took to run |

Exactly one of `result` and `error` is non-null in each entry.

### Authentication in bulk requests

Each sub-request **inherits the HTTP headers of the outer request**.  Any `Authorization`
bearer token present on the outer call is therefore forwarded to every sub-handler, which
enforces it exactly as it would for a standalone call.  There is no mechanism to supply
different auth tokens for different sub-requests within a single bulk call.

The bulk endpoint itself is declared with `auth = noAuth`, but each sub-handler enforces
its own requirement.  A sub-request to a protected endpoint without a valid token returns
an `LSError` with `http = 401` in the sub-response body — it does **not** cause the outer
bulk request to fail.

### Testing the bulk endpoint

Because the bulk handler dispatches sub-requests through the registered route table, you
must exercise it through the full HTTP pipeline (`serverRuntime.handle()`), not through
the typed `ApiHttpHandler.test()` helper — the typed helper calls the handler lambda
directly and bypasses the router, so sub-request path lookups would fail.

> To wrap this in a test class, annotate your test method with `@Test` — see
> [Testing Your Server](testing.md) for the complete pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/BulkEndpointsSamples.kt#bulk-test -->
```kotlin
fun bulkTest() = BulkServer.testBlocking(settings = {}) {
    // Drive /meta/bulk through the full HTTP pipeline so the framework can resolve
    // sub-request paths via the registered route table.  ApiHttpHandler.test() would
    // bypass routing and cannot match sub-request paths, so we use serverRuntime.handle().
    val response = serverRuntime.handle(
        HttpRequest<PathSpec>(
            path = RawHttpEndpoint(asString = "/meta/bulk", method = HttpMethod.POST),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders.EMPTY,
            domain = "example.com",
            protocol = "https",
            sourceIp = "local",
            body = TypedData.text(
                """{"ping":{"path":"/ping","method":"GET"},"gone":{"path":"/missing","method":"GET"}}""",
                MediaType.Application.Json,
            ),
        )
    )

    // The outer bulk endpoint always returns HTTP 200; per-sub-request errors appear in the body.
    check(response.status.code == 200)

    val body = response.body!!.text()
    val results = Json { ignoreUnknownKeys = true }
        .decodeFromString<Map<String, BulkResponse>>(body)

    // Successful sub-request: result holds the JSON-encoded response body, error is null.
    val ping = results["ping"]!!
    check(ping.result != null)
    check(ping.error == null)

    // Failed sub-request: error carries the HTTP status (404); result is null.
    val gone = results["gone"]!!
    check(gone.error != null)
    check(gone.error!!.http == 404)
    check(gone.result == null)
}
```

`testBlocking` provides a live `ServerRuntime` (as a `context` receiver), so
`serverRuntime` and `serverRuntime.handle()` are available directly without wrapping in
`runBlocking`.

### Telemetry: per-sub-request spans

Each sub-request produces its own telemetry span named `"$METHOD $route"` (e.g.
`"GET /ping"`), with the standard `http.*` attributes and a captured `http.status_code`.
These spans are children of the root `POST /meta/bulk` span.  A sub-request that throws
an `HttpStatusException` marks its handler span as errored while the parent bulk span
remains successful, because the outer endpoint always returns HTTP 200.

This means your APM or tracing dashboard shows the real per-endpoint cost of each bulk
call, not just a single opaque entry.

### Limits and considerations

- Sub-requests run in parallel (`coroutineScope` + `async`/`awaitAll`).  They share the
  same JVM thread pool, so very large batches can still saturate the server.  Consider
  limiting client batch sizes in your own application logic.
- The bulk endpoint is **not transactional**.  Partially failed batches do not roll back
  successful sub-requests.
- Query strings on `path` (e.g. `"/posts?limit=5"`) are parsed and forwarded to the
  sub-handler's `queryParameters`.

---

## Part 2: Funnel Analytics

### What it is

`FunnelEndpoints` is a ready-made analytics module for tracking user journeys through
multi-step flows.  A **funnel** is any named sequence of steps — onboarding, checkout,
sign-up, etc.  For each user attempt the client calls:

- `start` when the flow begins (receives a `Uuid` instance ID)
- `step` as the user advances (records the step number)
- `error` whenever a recoverable error occurs (records a short error description)
- `success` when the user completes the flow

If the user abandons the flow, the `FunnelInstance` expires automatically based on the
`expireAfterMinutes` supplied at start.

`FunnelEndpoints` stores `FunnelInstance` records in your database, runs a daily
summarisation that produces `FunnelSummary` records per funnel name, and exposes a
summary REST API for dashboards.

> The examples in Part 2 are **illustrative** — they are not backed by compiled regions
> in the drift-checked sample source.  The API signatures are verified against
> `typed/src/main/kotlin/.../FunnelEndpoints.kt`.

### Mounting

`FunnelEndpoints` is a `ServerBuilder` class that takes a database and an
`AuthRequirement` controlling read access to the summary/instance data:

```kotlin
// Illustrative — not a drift-checked sample.
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.typed.FunnelEndpoints

object MyServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val funnels = path.path("funnels") include FunnelEndpoints(
        database = database,
        read     = AuthRequirement.IsAdmin,  // who can read summaries and instances
    )
}
```

The `read` parameter controls access to the summary and instance REST endpoints.  The
`start`, `step`, `error`, and `success` write endpoints are declared `noAuth` (or
`read or noAuth`) so any client can call them.

### Client-side flow

The client drives the funnel lifecycle through four paths:

```
// Illustrative.
POST /funnels/start            → returns a Uuid (the instance id)
POST /funnels/step/{id}        → body: Int (the step number reached)
POST /funnels/error/{id}       → body: String (a short error description)
POST /funnels/success/{id}     → marks the instance complete
```

A `FunnelStart` request body:

```json
// Illustrative.
{
  "funnel":             "checkout",
  "userAgent":          "MyApp/2.3 (iOS 17)",
  "version":            "2.3.1",
  "expireAfterMinutes": 60,
  "expectedErrorRate":  0.05
}
```

| Field                | Type     | Default | Description |
|----------------------|----------|---------|-------------|
| `funnel`             | `String` | —       | Name of the funnel (`"checkout"`, `"onboarding"`, …) |
| `userAgent`          | `String` | —       | Client version string for segmentation |
| `version`            | `String` | —       | App/backend version for comparing across releases |
| `expireAfterMinutes` | `Int`    | 20      | Automatic expiry for abandoned instances |
| `expectedErrorRate`  | `Float`  | 0.05    | Threshold for health status computation |

A minimal client sequence:

```
// Illustrative.
id = POST /funnels/start  { funnel: "checkout", userAgent: "...", version: "..." }
try {
    POST /funnels/step/{id}   body: 1   // billing details entered
    POST /funnels/step/{id}   body: 2   // payment submitted
    POST /funnels/success/{id}
} catch (err) {
    POST /funnels/error/{id}  body: err.message
}
```

### Daily summarisation

A scheduled task runs at 08:00 in the configured time zone (default `America/Denver`).
For each funnel name seen in the previous day's instances it produces a `FunnelSummary`:

| Field               | Type               | Description |
|---------------------|--------------------|-------------|
| `funnel`            | `String`           | The funnel name |
| `date`              | `LocalDate`        | The day being summarised |
| `success`           | `Float`            | Fraction that succeeded without errors |
| `successAfterError` | `Float`            | Fraction that succeeded after at least one error |
| `error`             | `Float`            | Fraction that had an error and did not succeed |
| `abandoned`         | `Float`            | Fraction with no errors and no success |
| `count`             | `Int`              | Total instances in the window |
| `status`            | `HealthStatus.Level` | `OK`, `WARNING`, or `ERROR` vs. `expectedErrorRate` |

Trigger summarisation manually for a given date:

```
// Illustrative.
POST /funnels/summarize-now   body: "2026-06-15"   // null = today
```

### Querying summaries

`FunnelEndpoints` mounts standard `ModelRestEndpoints` for both `FunnelSummary` (at
`.../summary/rest`) and `FunnelInstance` (at `.../instance/rest`).  Access requires the
`read` auth configured at construction time.

A convenience endpoint returns all summaries for a specific date:

```
// Illustrative.
GET /funnels/summaries/{date}   e.g. /funnels/summaries/2026-06-15
```

---

## What's Next

- **`MetaEndpoints` full reference** — the bulk endpoint is one of several tools mounted
  under `/meta`.  See the [Typed Endpoints](typed-endpoints.md) chapter for the complete
  list including `/meta/health`, `/meta/openapi`, and the admin UI.
- **Model REST endpoints** — `FunnelEndpoints` uses `ModelRestEndpoints` internally.
  See [Model REST](model-rest.md) for the full CRUD pattern and query DSL.
- **Testing** — [Testing Your Server](testing.md) covers the complete `@Test` +
  `testBlocking` pattern and how to assert raw HTTP responses.
