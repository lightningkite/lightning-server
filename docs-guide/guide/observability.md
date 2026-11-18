# Observability

Every request, WebSocket phase, task and schedule runs inside a trace span with RED (rate, errors, duration)
metrics, sent to the `TelemetryBackend` from Service Abstractions.  Telemetry is off until you choose a backend with
the built-in `telemetry` setting; then you can add spans, attributes and metrics of your own.  For compliance
records of who saw or changed what, see [Audit log](#audit-log).

## Turning it on

<!-- sample: com/lightningkite/lightningserver/guide/samples/ObservabilitySamples.kt#obs-imports -->
```kotlin
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.currentRequestTrace
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ClientTelemetryEndpoints
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.LoggingTelemetryBackend
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.*
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ObservabilitySamples.kt#obs-server -->
```kotlin
object TracedShopServer : ServerBuilder() {
    init {
        // Registers the "logging" URL schemes.
        LoggingTelemetryBackend
        // Registers "otlp-grpc", "otlp-http", "otlp-https", "console", "log", "dev" and "debounced-dev".
        OtelTelemetryBackend
        registerBasicMediaTypeCoders()
    }

    val cache = setting("cache", Cache.Settings())

    private val cartSize = TelemetryKey.OfLong("shop.cart.size")
    private val ordersPlaced = Runtime.Cached {
        contextOf<Engine>().telemetryCounter("shop.orders.placed", MetricUnit.Occurrences, defaultDimensions = emptySet())
    }

    val checkout = path.path("checkout").post bind ApiHttpHandler(
        summary = "Check out",
        auth = noAuth,
        implementation = { items: List<String> ->
            currentRequestTrace()?.enrich(cartSize to items.size.toLong())

            val total = serverRuntime.telemetryTrace("price-cart") { trace ->
                trace.log(LogLevel.Debug) { "Pricing ${items.size} items" }
                items.size * 500
            }

            ordersPlaced().increment()
            total
        },
    )

    // Lets client apps send their spans and logs through the server.
    val clientTelemetry = path.path("telemetry") module ClientTelemetryEndpoints(cache)
}
```

Backends register their URL schemes when first referenced, so reference them in `init`; an unregistered scheme fails
at startup.  Then pick one in settings:

```json
{
  "telemetry": { "url": "otlp-grpc://otel-collector:4317" }
}
```

| URL | Output | Use |
|---|---|---|
| `noop` (default) | Nothing | Telemetry off; every call is a no-op |
| `logging`, `logging-nocolor` | Each finished trace printed as a tree, metrics folded in | Local development |
| `dev`, `dev://path/to/file?color=false&metric_frequency=30s` | OTel SDK with tree-style console (or file) output | Local development with the real SDK |
| `debounced-dev` | As `dev`, collapsing repeated spans (`debounce`, `debounce_min` parameters) | Noisy local servers |
| `console` | OTel SDK, spans/metrics/logs printed to stdout | Debugging exports |
| `log` | OTel SDK's logging exporters | Debugging exports |
| `otlp-grpc://host:port` | OTLP over gRPC (default `localhost:4317`) | Production (Grafana, Honeycomb, Jaeger, Datadog agent...) |
| `otlp-http://host:port`, `otlp-https://host:port` | OTLP over HTTP(S) (`/v1/traces`, `/v1/metrics`, `/v1/logs`) | Production |

The `logging` backend prints something like:

```
12:34:56.789
✓ lightningserver.POST /checkout (3.1ms)
   http.request.method=POST http.route=/checkout shop.cart.size=2
   └─ ✓ lightningserver.handler (2.4ms)
         ├─ ✓ lightningserver.price-cart (0.2ms)
         └─ ◆ lightningserver.shop.orders.placed = 1.00 {occurrence}
```

## What you get without writing code

Each HTTP request gets a root span named `<method> <route>` (for example `POST /checkout`; `unmatched` for requests
no route handles) with child spans `handler` and, on errors, `exceptionHandler`.  Its attributes:

| Attribute | Value |
|---|---|
| `http.request.method`, `http.route`, `http.target`, `http.scheme`, `http.host`, `net.peer.ip` | Set when the request starts |
| `http.response.status_code` | The response status |
| `error.type` | The exception's class name, when a handler threw |

WebSocket phases (`willConnect`, `didConnect`, messages, `disconnect`), tasks, schedules, startup and pre-deploy
tasks get spans too.  Service Abstractions backends that instrument themselves (cache, files, ...) add child spans
for their operations.  Exceptions that produce a 5xx are reported to the backend, recorded on the span with an
`error.fingerprint`.

Every span also records RED metrics: `<owner>.client.operation.count` and `<owner>.client.operation.duration`
(seconds), with dimensions `system`, `operation` (the span name) and `outcome` (`ok` or `error`).  Spans the server
opens are owned by the engine, so the HTTP metrics are `lightningserver.client.operation.*` with `operation` like
`POST /checkout`.  A handler that maps an exception to an error response still completes, so for HTTP the
`outcome` is `ok`; use `http.response.status_code` on the span for error rates by status.

### Continuing a caller's trace

A W3C `traceparent` header on an HTTP request or WebSocket connect becomes the parent of the root span, so client
and server spans share one trace.  If your CORS settings restrict headers (anything but `*`), allow `traceparent`;
see [CORS and interceptors](cors-interceptors.md).

### Turning tracing off for a handler

Override `HttpHandler.traced` to return `false` and the handler gets no request span, no RED metrics, and nothing
beneath it is traced.  `ClientTelemetryEndpoints.ingest` does this so uploads don't trace themselves.

## Custom spans and attributes

- `serverRuntime.telemetryTrace("name") { trace -> ... }` opens a child of the current span, named
  `lightningserver.name`, with its own RED metrics.  Pass `attributes` to set some at start and `dimensions` to promote
  low-cardinality attributes onto its metrics.
- `trace.enrich(key to value)` adds attributes learned during the operation; `trace.log(level) { message }` writes
  a log record correlated with the span, building the message only if that level is enabled.
- `currentRequestTrace()` is the request's root span wherever you are beneath it: use it for facts about the
  request as a whole, such as who the caller turned out to be.
- `telemetryAttributes(attributes) { ... }` adds attributes to everything recorded inside the block without opening
  a span.

Define keys once as `TelemetryKey.OfString`, `OfLong`, `OfDouble` or `OfBoolean`; `TelemetryKeys` has the OpenTelemetry
semantic-convention keys (`TelemetryKeys.Http.route`, `TelemetryKeys.User.id`, ...).

To report an exception you caught and handled, call `serverRuntime.reportException(e)`: it is recorded on the
current span, or as an error log outside one.

## Custom metrics

| Function | Instrument |
|---|---|
| `telemetryCounter(name, unit, defaultDimensions)` | `increment(amount = 1.0)` |
| `telemetryHistogram(name, unit, defaultDimensions)` | `record(amount)` |
| `telemetryInFlight(name, defaultDimensions)` | `lease()` ... `release()`, an up/down count of work in progress |
| `telemetryGauge(name, unit) { sample }` | A value the exporter samples; returns an `AutoCloseable` to unregister |

They are extensions on the engine, so create them in a `Runtime.Cached { contextOf<Engine>()... }` as above, which
makes one instrument per running server.  `defaultDimensions` lists which ambient attributes become metric labels;
spans carry every attribute, but metrics carry only these, so keep them low-cardinality (never user ids or paths).
`MetricUnit` has `Seconds`, `Bytes`, `Occurrences` and others.

## Client telemetry

`ClientTelemetryEndpoints(cache)` adds `POST ingest` (`clientTelemetry.ingest` in the generated SDK), which takes a
`TelemetryBatch` of spans and logs from a client app such as a KiteUI app and passes it to the server's backend, so
clients ship no telemetry credentials.

- The server sets the identity: `service.name` is `<projectName>-client`, `user.id` comes from the caller's
  authentication (anonymous callers are accepted), client-sent `user.*` attributes are dropped, and only the resource
  keys in `ClientTelemetryEndpoints.CLIENT_RESOURCE_KEYS` are kept.
- Uploads are rate limited per user, else per IP: a burst of about 24, then one per 5 seconds.  The
  `clientTelemetryRateLimit` setting configures it; `null` turns it off.  See [Rate limiting](rate-limiting.md).
- Batches over 2000 records, records over 128 attributes, values over 4096 characters, malformed ids, and names not
  matching `[A-Za-z][A-Za-z0-9_.-]{0,63}` are rejected whole with 400.
- Ingested spans produce no metrics.

## Backend settings

`telemetry` is a `TelemetryBackend.Settings` object.  Besides `url`:

| Field | Default | Purpose |
|---|---|---|
| `sampling` | `null` (keep everything) | `{ "ratio": 0.1, "parentBased": true }` keeps 10% of traces, honoring the caller's decision |
| `maxSpansPerSecond`, `maxLogsPerSecond` | `null` (unlimited) | Drop beyond this rate to cap cost |
| `batching` | `{ "frequency": "5m", "maxQueueSize": 2048, "maxSize": 512, "exportTimeout": "30s" }` | Export batching; `traceReportBatching`, `metricReportBatching`, `logReportBatching` override per signal |
| `spanLimits` | `maxAttributeValueLength` 1024, `maxNumberOfAttributes` 128, ... | Truncate oversized spans |
| `logLimits` | `maxBodyLength` 8192, `maxStackTraceDepth` 50 | Truncate oversized logs |

The `dev` and `debounced-dev` backends export every span immediately and ignore batching and sampling.

## Testing telemetry

Set the backend directly in a test with `setStatic`:

```kotlin
TracedShopServer.testBlocking(settings = { telemetrySettings setStatic myRecordingBackend }) {
    TracedShopServer.checkout.test(null, listOf("apple", "pear"))
}
```

`telemetrySettings` is in `com.lightningkite.lightningserver.definition`.  Any `TelemetryBackend` implementation
works; `LoggingTelemetryBackend(out = PrintWriter(...))` captures the printed tree.

## Audit log

The `audit` module records, in your database, which audited records reached which clients, what changed them, which
queries touched them, and authentication events.  Unlike telemetry, these records are complete (never sampled) and
fail closed.

```kotlin
// build.gradle.kts
implementation("com.lightningkite.lightningserver:audit:$lightningServerVersion")
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ObservabilitySamples.kt#audit-imports -->
```kotlin
import com.lightningkite.lightningserver.audit.*
import com.lightningkite.lightningserver.sessions.install
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ObservabilitySamples.kt#audit-server -->
```kotlin
@Queryable
data class PatientChart(
    override val _id: ID = ID(Uuid.random()),
    @Audited val name: String,
    @Audited val diagnosis: String,
    val visits: Int = 0,
) : AuditedModel<PatientChart.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: Uuid) : AuditedModel.Id<Uuid, ID> {
        override val asUuid: Uuid get() = raw
    }
}

object ClinicServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    object Audit : AuditingBuilder.WithStandardRequestInfo(database) {
        val disclosures = install(AuditDisclosureLog())
        val authEvents = install(AuthEventLog())
        val mutations = AuditMutationLog()
        val queries = AuditQueryLog()
    }

    val audit = path.path("audit") include Audit

    val chartInfo = database.modelInfo<HasId<*>?, PatientChart, PatientChart.ID>(
        auth = noAuth,
        tableName = "PatientChart",
        log = { it.logAuditedFieldChanges(Audit.mutations).logQueries(Audit.queries) },
        permissions = { ModelPermissions.allowAll() },
    )

    val charts = path.path("charts") include ModelRestEndpoints(chartInfo)

    init {
        registerBasicMediaTypeCoders()
    }
}
```

An audited model implements `AuditedModel` with an id wrapping a `Uuid`.  Mark the fields worth itemizing
`@Audited`; records then say which of those fields were disclosed or changed.  Each field permanently takes a bit
index that is never reused, so mark only what matters.

`AuditingBuilder` sets up the shared parts, origins (one row per request, socket connect or task that something was
recorded under, in `Audit_OriginRecords`) and the model registry, and builds whichever logs you call:

| Log | Records | Enabled by | Table |
|---|---|---|---|
| `AuditDisclosureLog()` | Each audited record sent to a client, and which audited fields held a value | `install(...)` | `Audit_DisclosureRecords` |
| `AuthEventLog()` | Session created, ended or rejected; proofs accepted, rejected or issued | `install(...)` (the `sessions` overload) | `Audit_AuthEventRecords` |
| `AuditMutationLog()` | Creates, modifies and deletes, with the new values of changed audited fields | `table.logAuditedFieldChanges(log)` | `Audit_MutationRecords` |
| `AuditQueryLog()` | Every query and write, including the server's own | `table.logQueries(log)` | `Audit_QueryRecords` |

Wrap tables in `ModelInfo`'s `log` hook, as above: it applies to `table()`, `table(auth)` and `baseTable()` alike.
Only `dangerouslyDirectTable()`, which requires opting in to `@UnauditedDatabaseAccess`, bypasses it.

Gotchas:

- Fail closed.  If a disclosure or query record can't be written, the response or query doesn't happen: an outage of
  the audit database is an outage of every endpoint returning audited models.
- The registry assigns models and field bits during pre-deploy tasks, from the models your endpoints use.  Run
  pre-deploy before serving, and don't audit a model no endpoint uses: its changes throw after they are made.
- Disclosure assumes masked fields are empty (`null`, `0`, `""`, empty collections).  A field masked to anything
  else is always reported as disclosed.
- Install each log at most once, or every event is recorded twice.
- To record different request details on origins, extend `AuditingBuilder<YourInfo>(database, OriginRecording.RequestInfo(serializer) { request -> ... })`
  instead of `WithStandardRequestInfo` (subject, session, IP and request ids).

## Next

- [Meta endpoints](meta-admin.md): health checks.
- [Rate limiting](rate-limiting.md): limits on client telemetry and other endpoints.
- [AWS deployment](aws-deployment.md): the collector and dashboards Terraform can provision.
- [Settings](settings.md): where `telemetry` comes from.
