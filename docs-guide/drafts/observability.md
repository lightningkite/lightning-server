> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Observability

Lightning Server ships first-class observability support through the **TelemetryBackend** SPI from `service-abstractions`.  Every inbound HTTP request is automatically wrapped in a distributed trace span with RED (Rate/Error/Duration) metrics.  You can layer in custom spans, histograms, counters, and gauges using the same DSL, and the backend is swappable via a URL string in `settings.json` — no code changes needed to switch from local console output to Grafana/OTLP in production.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.services.LoggingTelemetryBackend
import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.*
```

## How Telemetry is Configured

`telemetrySettings` is a built-in global `ServerSetting` in `core`.  Its default is `TelemetryBackend.Settings()`, which uses the `"noop"` URL — telemetry disabled.

To enable it, set the `"telemetry"` key in `settings.json`:

```json
{
  "telemetry": { "url": "logging" }
}
```

Or in code (useful for tests):

```kotlin
// Illustrative — inside a ServerBuilder or engine's settings lambda.
// telemetrySettings is imported from com.lightningkite.lightningserver.definition.
object Server : ServerBuilder() {
    // ... your settings and endpoints ...
}
```

You do not need to declare `telemetrySettings` yourself — it is always present.

### Registering Non-Default Backends

Like other service backends, each telemetry backend's URL scheme handler is only registered when its companion object is first touched by the JVM class loader.  Reference the backend objects in your `ServerBuilder`'s `init` block so they are registered before `settings.json` loads:

```kotlin
// Illustrative — verified against demo/Server.kt (init block).
object Server : ServerBuilder() {
    init {
        LoggingTelemetryBackend   // registers "logging" and "logging-nocolor"
        OtelTelemetryBackend      // registers "console", "log", "dev", "debounced-dev",
                                  //   "otlp-grpc", "otlp-http", "otlp-https"
    }
}
```

The `"noop"` scheme is always registered (built in to `TelemetryBackend.Settings` itself) and requires no init reference.

## Available URL Schemes

| URL | Backend | When to use |
|---|---|---|
| `noop` | Built-in no-op | Default; telemetry disabled. Zero overhead. |
| `logging` | `LoggingTelemetryBackend` | Local development. Prints a human-readable ANSI-colored span tree to stdout. Requires `LoggingTelemetryBackend` in init block. |
| `logging-nocolor` | `LoggingTelemetryBackend` | Same as `logging` but without ANSI color codes (e.g. for CI logs). |
| `console` | `OtelTelemetryBackend` | OTel SDK with a pretty-print console exporter. Good for staging. Requires `OtelTelemetryBackend` in init block. |
| `log` | `OtelTelemetryBackend` | OTel SDK with the JUL/SLF4J logging exporter. |
| `dev` | `OtelTelemetryBackend` | Development-oriented tree-style output, color configurable via query params. |
| `otlp-grpc://host:port` | `OtelTelemetryBackend` | Production: exports via OTLP/gRPC to Grafana, Jaeger, Honeycomb, etc. Defaults to `localhost:4317`. |
| `otlp-http://host:port` | `OtelTelemetryBackend` | Production: exports via OTLP/HTTP. Defaults to `localhost:4318`. |
| `otlp-https://host:port` | `OtelTelemetryBackend` | Same as `otlp-http` with TLS. |

### `dev` Query Parameters (Illustrative)

The `dev` scheme accepts optional query parameters:

```
"dev?color=false&metric_frequency=30s&log_delay=200"
```

| Parameter | Default | Meaning |
|---|---|---|
| `color` | `true` | Set to `false` to disable ANSI colors. |
| `metric_frequency` | `60s` | How often metrics are printed. Any Kotlin `Duration` string. |
| `log_delay` | `0` | Delay in milliseconds before correlating logs to spans (for log tail ordering). |

The `debounced-dev` scheme adds `debounce` (window in ms) and `debounce_min` (min count before printing) to reduce noise from repetitive operations.

## The `logging` Backend in Action

When `url = "logging"` the backend buffers spans until the root span completes and then prints an indented tree:

```
12:34:56.789
✓ demo.POST /api/users (12.3ms)
   http.method=POST http.route=/api/users http.status_code=201
   ├─ ✓ demo.db.insertOne (9.1ms)
   │     db.collection.name=users
   └─ ◆ demo.rows = 1.0000 {occurrence}
```

`✓` means the span succeeded; `✗` means it threw an exception.  Histograms and counters recorded inside a span appear as `◆` lines.

## Free HTTP Observability

Every HTTP request handled by a Lightning Server engine is automatically instrumented with a span and RED metrics.  You get the following at zero cost:

- **Span name**: `"$method $route"` (e.g. `"POST /api/users"`)
- **Initial attributes** on entry:
  - `http.request.method`
  - `http.route` (pattern, not the literal URL)
  - `http.target` (literal path)
  - `http.scheme`
  - `http.host`
  - `net.peer.ip` (source IP)
- **Enriched attributes** after completion:
  - `http.response.status_code`
  - `error.type` (simple class name of any exception that was mapped to a non-5xx status)
- **RED metrics** keyed by `{system, operation, outcome}`:
  - `*.client.operation.count` — counter of requests
  - `*.client.operation.duration` — histogram of latency in seconds

This means Grafana (or any OpenTelemetry backend) can show you request rate, error rate, and p99 latency broken down by route and outcome without any configuration on your part.

## Custom Spans

Use `Namespaced.telemetryTrace` to open a child span for a logical operation inside a handler.  `ServerBuilder` objects implement `Namespaced`, so you can call it directly:

```kotlin
// Illustrative — runs inside a handler where a ServerRuntime context is available.
object MyServer : ServerBuilder() {
    val doWork = path.path("work").post bind HttpHandler {
        // Open a custom span named "myServer.processItem".
        // The span inherits the ambient HTTP span as its parent automatically.
        telemetryTrace("processItem") { span ->
            // Attach high-cardinality attributes to the span only.
            span.enrich(TelemetryAttributes {
                put(TelemetryKeys.Db.operationName, "findAndProcess")
                put(TelemetryKeys.Db.collectionName, "items")
            })

            // Do real work here.
            performExpensiveOperation()

            // Log correlated to this span.
            span.log(LogLevel.Info, "Item processed successfully")

            HttpResponse.plainText("done")
        }
    }
}
```

`telemetryTrace` is a `suspend` extension on `Namespaced`.  It automatically parents the new span under the ambient span from the coroutine context.  You do not need to pass a parent span explicitly.

### Lazy Log Guards

The `isLoggable` check avoids building expensive log strings when the level is disabled:

```kotlin
// Illustrative.
span.log(LogLevel.Debug) { "expensive ${computeDebugInfo()}" }
// message lambda is only called when Debug is loggable on the backend.
```

## Custom Metrics

Pre-allocate metric instruments once at server-definition time (in the `ServerBuilder`), then record from handlers.  Instruments are cheap no-ops when the `noop` backend is active.

```kotlin
// Illustrative.
object MyServer : ServerBuilder() {
    // Declare a histogram at module load time.  defaultDimensions narrows which
    // ambient attributes become metric labels — keep this set small.
    private val rowsHistogram = telemetryHistogram(
        "myServer.db.rows_returned",
        MetricUnit.Occurrences,
        defaultDimensions = setOf(TelemetryKeys.Db.operationName)
    )

    private val requestCounter = telemetryCounter(
        "myServer.custom.requests",
        MetricUnit.Occurrences,
        defaultDimensions = emptySet()
    )

    val query = path.path("query").get bind HttpHandler {
        val rows = fetchRows()
        rowsHistogram.record(rows.size.toDouble())
        requestCounter.increment()
        HttpResponse.json(rows)
    }
}
```

`telemetryHistogram` / `telemetryCounter` are extensions on `Namespaced` (see `telemetryDsl.kt`).  For in-flight concurrency tracking, use `telemetryInFlight`.  For a sampled gauge (e.g. queue depth), use `telemetryGauge`.

## The Ambient Attribute Bag

All telemetry in Lightning Server is built around an **ambient attribute bag** carried in the coroutine context.  Attributes added higher in the call tree (e.g. `http.route` from the engine) are automatically visible to child spans and projected metrics, without any manual threading.

Use `telemetryAttributes` to enrich the bag for the duration of a block without opening a span:

```kotlin
// Illustrative.
suspend fun processForUser(userId: String) {
    telemetryAttributes(TelemetryAttributes {
        put(TelemetryKeys.Enduser.id, userId)
    }) {
        // Every span and metric recorded here sees enduser.id automatically.
        doSomethingForUser()
    }
}
```

## Custom Attribute Keys

Use `TelemetryKey` to define typed attribute keys.  Pre-allocate them as `val` fields — backends cache their native key objects at first use:

```kotlin
// Illustrative.
private val cacheHit    = TelemetryKey.OfBoolean("cache.hit")
private val rowCount    = TelemetryKey.OfLong("db.response.returned_rows")
private val tenantId    = TelemetryKey.OfString("app.tenant.id")
```

`TelemetryKeys` (the generated OTel semantic-convention catalog) provides pre-made keys for standard attributes: `TelemetryKeys.Http`, `TelemetryKeys.Db`, `TelemetryKeys.Error`, etc.  Use those in preference to string literals for compatibility with OTel-aware backends.

## Error Reporting

Call `TelemetryBackend.reportError` (accessible via the `ServerRuntime`) to report an already-caught exception:

```kotlin
// Illustrative.
context(runtime: ServerRuntime)
suspend fun safeFetch(): Data? {
    return try {
        fetchFromRemote()
    } catch (e: RemoteException) {
        // Report the exception without re-throwing.
        runtime.telemetryBackend.reportError(e, TelemetryAttributes {
            put(TelemetryKeys.Http.url, "https://example.com/api")
        })
        null
    }
}
```

When there is an active span (the normal case inside a handler), `reportError` records the exception on the span and marks it errored with `StatusCode.ERROR`.  When called outside any span (background work, startup), it emits a standalone ERROR log record correlated to the telemetry system.

Lightning Server's built-in exception mapping also reports non-5xx status errors on the enclosing HTTP span automatically.

## Advanced `TelemetryBackend.Settings`

For production deployments you may need to tune batching, sampling, and rate limits via the full `Settings` object in `settings.json`:

```json
{
  "telemetry": {
    "url": "otlp-grpc://otel-collector:4317",
    "sampling": {
      "ratio": 0.1,
      "parentBased": true
    },
    "maxSpansPerSecond": 500,
    "traceReportBatching": {
      "frequency": "5m",
      "maxQueueSize": 4096,
      "maxSize": 512,
      "exportTimeout": "30s"
    },
    "spanLimits": {
      "maxAttributeValueLength": 1024,
      "maxNumberOfAttributes": 128
    }
  }
}
```

Key fields:

| Field | Default | Purpose |
|---|---|---|
| `sampling.ratio` | `1.0` | Fraction of traces to sample (0.0–1.0). |
| `sampling.parentBased` | `true` | Respect the sampling decision from the upstream caller (W3C trace context). |
| `maxSpansPerSecond` | `null` (unlimited) | Rate-limit spans to prevent cost spikes. |
| `maxLogsPerSecond` | `null` (unlimited) | Rate-limit log records. |
| `traceReportBatching.frequency` | `5m` | How often the batch exporter flushes. |
| `spanLimits.maxAttributeValueLength` | `1024` | Truncates long attribute values. |
| `logLimits.maxBodyLength` | `8192` | Truncates long log message bodies. |
| `logLimits.maxStackTraceDepth` | `50` | Truncates deep stack traces. |

## Health Checks

> **Illustrative** — A built-in health check endpoint is not yet part of Lightning Server core at the time of this writing. The recommended pattern is a simple `GET /health` endpoint that invokes each service setting and returns `200` if all resolve, or `503` if any throw.

```kotlin
// Illustrative pattern — not a drift-checked sample.
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache    = setting("cache", Cache.Settings())

    val health = path.path("health").get bind HttpHandler {
        try {
            database().ping()   // illustrative — actual API may differ
            cache().ping()      // illustrative
            HttpResponse.plainText("ok")
        } catch (e: Exception) {
            HttpResponse.plainText("unhealthy: ${e.message}", HttpStatus.ServiceUnavailable)
        }
    }
}
```

Pair this endpoint with your load balancer's health probe and your observability backend's uptime monitor.

## What's Next

- **Services** — `TelemetryBackend` follows the same settings-URL pattern as `Cache`, `Database`, etc.; see the [Services & Settings](../guide/services.md) guide.
- **Engine tuning** — the `shutdownDrainTimeout` and request body caps discussed in [Engine Tuning & Reliability](engine-tuning.md) are separate from telemetry but affect the latency histogram you see.
