> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Bulk Requests & Funnel Analytics

This chapter covers two distinct features that both live in the `:typed` module:

- **Bulk requests** (`MetaEndpoints.bulk`) — send many typed API calls in a single HTTP
  request and receive all results in one response.
- **Funnel analytics** (`FunnelEndpoints`) — track user journeys through multi-step
  application flows and surface daily health summaries.

---

## Part 1: Bulk Requests

### What it is

`MetaEndpoints` exposes a `POST /meta/bulk` endpoint that accepts a map of named
sub-requests, fans them out to the real endpoint handlers in parallel, and returns a
map of named results.  The outer request always returns HTTP 200 — per-sub-request
errors are captured in the response body, not surfaced as HTTP error codes.

This is useful for:

- Mobile or web clients that need several independent API responses to render a single
  screen, and want to reduce round-trips.
- Batch operations where each call is logically independent (not a transaction).
- Dashboard pages that aggregate data from several endpoints at once.

### Mounting the bulk endpoint

The bulk endpoint is part of `MetaEndpoints`, which you mount with the `module` infix
from the `typed` module:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache    = setting("cache", Cache.Settings())

    // Mounts at /meta, including /meta/bulk
    val meta = path.path("meta") module MetaEndpoints(
        packageName = "com.example.myapp",
        database    = database,
        cache       = cache,
    )
}
```

`MetaEndpoints` also adds `/meta/health`, `/meta/openapi`, `/meta/docs`, and other
diagnostic endpoints.  See [Typed Endpoints](../guide/typed-endpoints.md) for the full list.

### Request shape

`POST /meta/bulk` body is a JSON object whose keys are caller-chosen names and whose
values are `BulkRequest` objects:

```json
{
  "userProfile": {
    "path": "/users/me",
    "method": "GET"
  },
  "recentPosts": {
    "path": "/posts?limit=5",
    "method": "GET"
  },
  "createDraft": {
    "path": "/posts/draft",
    "method": "POST",
    "body": "{\"title\":\"Hello\",\"body\":\"World\"}"
  }
}
```

`BulkRequest` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `path` | `String` | yes | Endpoint path, optionally with a `?query=string` suffix |
| `method` | `String` | yes | HTTP method (`"GET"`, `"POST"`, `"PUT"`, `"PATCH"`, `"DELETE"`) |
| `body` | `String?` | no | JSON-encoded request body; omit or pass `null` for GET/DELETE |

### Response shape

The response is a JSON object with the same keys as the request.  Each value is a
`BulkResponse`:

```json
{
  "userProfile": {
    "result": "{\"_id\":\"abc\",\"email\":\"user@example.com\"}",
    "durationMs": 4
  },
  "recentPosts": {
    "result": "[{\"title\":\"First post\"}]",
    "durationMs": 12
  },
  "createDraft": {
    "error": {
      "http": 400,
      "detail": "validation-failed",
      "message": "Title must not be blank"
    },
    "durationMs": 1
  }
}
```

`BulkResponse` fields:

| Field | Type | Description |
|---|---|---|
| `result` | `String?` | JSON-encoded response body on success |
| `error` | `LSError?` | Structured error if the sub-request failed |
| `durationMs` | `Long` | Time the sub-handler took to run, in milliseconds |

Exactly one of `result` and `error` will be non-null.

### Authentication in bulk requests

Each sub-request inherits the HTTP headers of the original outer request.  Any
`Authorization` bearer token present on the outer request is therefore available to
every sub-handler, which enforces it exactly as it would for a standalone call.
There is no way to supply different auth tokens for different sub-requests in the
same bulk call.

### Telemetry: per-sub-request spans

Each sub-request produces its own telemetry span named `"$METHOD $route"` (e.g.
`"GET /users/me"`), with the standard `http.*` attributes and a captured
`http.status_code`.  These spans are children of the root `POST /meta/bulk` span.
A sub-request that throws an `HttpStatusException` marks its handler span as errored
while the parent bulk span remains successful, because the outer endpoint always returns
HTTP 200.

This means your APM / tracing dashboard shows the real per-endpoint cost of each bulk
call, not just a single opaque entry.

### Limits and considerations

- Sub-requests run in parallel (`coroutineScope` + `async`/`awaitAll`).  They share
  the same JVM thread pool, so a very large batch can still saturate the server.
  Consider limiting client batch sizes in your own application logic.
- The bulk endpoint is not transactional.  Partially failed batches do not roll back
  successful sub-requests.
- The bulk endpoint itself is declared with `auth = noAuth`, but each sub-handler
  enforces its own auth requirement.  A sub-request to an authenticated endpoint
  without a valid token returns an `LSError` with `http = 401` in the sub-response.
- Query strings on `path` (e.g. `"/posts?limit=5"`) are parsed and forwarded to the
  sub-handler's `queryParameters`.

---

## Part 2: Funnel Analytics

### What it is

`FunnelEndpoints` is a ready-made analytics module that tracks how users move through
multi-step flows in your application.  A **funnel** is any named sequence of steps
(onboarding, checkout, sign-up, etc.).  For each user attempt:

- Your client calls `start` when the flow begins.
- It calls `step` as the user advances.
- It calls `error` whenever a recoverable error occurs.
- It calls `success` when the user completes the flow.
- If the user abandons, the instance expires automatically.

`FunnelEndpoints` stores `FunnelInstance` records in your database, summarizes them
daily, and exposes a summary REST API for dashboards.

### Mounting

`FunnelEndpoints` is a `ServerBuilder` class that takes a database and an auth
requirement for admin read access:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val funnels = path.path("funnels") include FunnelEndpoints(
        database = database,
        read     = AuthRequirement.IsAdmin,  // who can read summaries and instances
    )
}
```

The `read` parameter controls access to the summary and instance REST endpoints.
The `start`, `step`, `error`, and `success` endpoints are declared with
`auth = noAuth` or `auth = read or noAuth` so any client can call them.

### Client-side flow

The client drives the funnel lifecycle through four endpoints:

```
POST /funnels/start        → returns a Uuid (the instance id)
POST /funnels/step/{id}    → body: Int (the step number reached)
POST /funnels/error/{id}   → body: String (a short error description)
POST /funnels/success/{id} → marks the instance complete
```

**`FunnelStart` body:**

```json
{
  "funnel":             "checkout",
  "userAgent":          "MyApp/2.3 (iOS 17)",
  "version":            "2.3.1",
  "expireAfterMinutes": 60
}
```

| Field | Type | Description |
|---|---|---|
| `funnel` | `String` | Name of the funnel (e.g. `"checkout"`, `"onboarding"`) |
| `userAgent` | `String` | Client version string for segmentation |
| `version` | `String` | App / backend version for comparing across releases |
| `expireAfterMinutes` | `Int` | Automatic expiry for abandoned instances |

A minimal client sequence in pseudo-code:

```
id = POST /funnels/start  { funnel: "checkout", ... }
try {
    POST /funnels/step/{id}   body: 1  // billing details entered
    POST /funnels/step/{id}   body: 2  // payment submitted
    POST /funnels/success/{id}
} catch (err) {
    POST /funnels/error/{id}  body: err.message
}
```

### Daily summarization

`FunnelEndpoints` schedules a daily summarization task that runs at 08:00 in the
configured `zone` (defaults to `America/Denver`).  For each funnel name seen in the
previous day's instances it produces a `FunnelSummary` with:

| Field | Type | Description |
|---|---|---|
| `funnel` | `String` | The funnel name |
| `date` | `LocalDate` | The day being summarized |
| `success` | `Float` | Fraction of attempts that succeeded without errors |
| `successAfterError` | `Float` | Fraction that succeeded after at least one error |
| `error` | `Float` | Fraction that had an error and did not succeed |
| `abandoned` | `Float` | Fraction with no errors and no success (dropped off) |
| `count` | `Int` | Total number of instances in the window |
| `status` | `HealthStatus.Level` | `OK`, `WARNING`, or `ERROR` based on `expectedErrorRate` |

Trigger summarization manually for a given date:

```
POST /funnels/summarize-now   body: "2026-06-15"   (or null for today)
```

### Querying summaries

`FunnelEndpoints` mounts REST endpoints for both `FunnelSummary` and `FunnelInstance`
at `.../summary/rest` and `.../instance/rest`, using the standard `ModelRestEndpoints`
pattern.  Access requires the `read` auth configured at construction time.

A convenience endpoint returns all summaries for a specific date:

```
GET /funnels/summaries/{date}   e.g. /funnels/summaries/2026-06-15
```

---

## What's Next

- **MetaEndpoints full reference** — the bulk endpoint is one of several tools mounted
  under `/meta`.  See the [Typed Endpoints](../guide/typed-endpoints.md) chapter for the
  complete list including `/meta/health`, `/meta/openapi`, and the admin UI.
- **Model REST endpoints** — `FunnelEndpoints` uses `ModelRestEndpoints` internally.
  See the [Model REST](model-rest.md) draft for the full CRUD pattern and query DSL.
- **Rate limiting** — if the bulk endpoint is publicly accessible, consider rate limiting
  the outer `/meta/bulk` path or exempting it and limiting the individual sub-routes.
