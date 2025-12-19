# OpenTelemetry Metrics Implementation Plan

## Objective

Add OpenTelemetry metrics tracking for HTTP handlers to measure:
- Status code distribution (histogram/counter per status code)
- Success/error rates
- Request duration (histogram)
- Request counts by route

## Current State

### Existing Telemetry
- **Spans/Traces**: Fully implemented in `implementationHelpers.kt`
- **Span Attributes**: `http.method`, `http.route`, `http.target`, `http.status_code`, etc.
- **Metrics**: **Not implemented** - only tracing exists

### Key Files
- `core/src/main/kotlin/com/lightningkite/lightningserver/runtime/implementationHelpers.kt` - Main request handling
- `core/src/main/kotlin/com/lightningkite/lightningserver/telemetry/kotlinify.kt` - OpenTelemetry helpers
- `core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntime.kt` - Runtime interface

### Available Infrastructure
- `OpenTelemetrySdkSub` class implements `Meter` interface (via delegation)
- Access via `runtime.openTelemetry?.get("key")` returns an object with both `Tracer` and `Meter`
- OpenTelemetry Meter API available: `counterBuilder()`, `histogramBuilder()`, `gaugeBuilder()`

## Implementation Plan

### Step 1: Create HTTP Metrics Registry

Create a new file to hold metrics instruments that can be lazily initialized:

**File: `core/src/main/kotlin/com/lightningkite/lightningserver/telemetry/HttpMetrics.kt`**

```kotlin
package com.lightningkite.lightningserver.telemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongHistogram
import io.opentelemetry.api.metrics.Meter

/**
 * HTTP metrics registry for OpenTelemetry.
 *
 * Provides standard HTTP server metrics following OpenTelemetry semantic conventions:
 * - http.server.request.duration: Duration of HTTP requests
 * - http.server.request.count: Total count of HTTP requests
 * - http.server.response.status: Count by status code category (2xx, 3xx, 4xx, 5xx)
 */
class HttpMetrics(meter: Meter) {

    /**
     * Histogram of HTTP request durations in milliseconds.
     * Attributes: http.method, http.route, http.status_code
     */
    val requestDuration: LongHistogram = meter.histogramBuilder("http.server.request.duration")
        .setDescription("Duration of HTTP server requests in milliseconds")
        .setUnit("ms")
        .ofLongs()
        .build()

    /**
     * Counter for total HTTP requests.
     * Attributes: http.method, http.route, http.status_code
     */
    val requestCount: LongCounter = meter.counterBuilder("http.server.request.count")
        .setDescription("Total count of HTTP server requests")
        .setUnit("{request}")
        .build()

    /**
     * Counter for HTTP responses by status category.
     * Attributes: http.method, http.route, http.status_category (2xx, 3xx, 4xx, 5xx)
     */
    val responsesByCategory: LongCounter = meter.counterBuilder("http.server.response.status.category")
        .setDescription("Count of HTTP responses by status category")
        .setUnit("{response}")
        .build()

    /**
     * Counter for errors (5xx responses).
     * Attributes: http.method, http.route, error.type
     */
    val errors: LongCounter = meter.counterBuilder("http.server.errors")
        .setDescription("Count of HTTP server errors")
        .setUnit("{error}")
        .build()

    companion object {
        // Attribute keys following OpenTelemetry semantic conventions
        val HTTP_METHOD: AttributeKey<String> = AttributeKey.stringKey("http.method")
        val HTTP_ROUTE: AttributeKey<String> = AttributeKey.stringKey("http.route")
        val HTTP_STATUS_CODE: AttributeKey<Long> = AttributeKey.longKey("http.status_code")
        val HTTP_STATUS_CATEGORY: AttributeKey<String> = AttributeKey.stringKey("http.status_category")
        val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")

        /**
         * Converts status code to category string.
         */
        fun statusCategory(code: Int): String = when (code) {
            in 100..199 -> "1xx"
            in 200..299 -> "2xx"
            in 300..399 -> "3xx"
            in 400..499 -> "4xx"
            in 500..599 -> "5xx"
            else -> "unknown"
        }
    }
}
```

### Step 2: Add Metrics to ServerRuntime

Add a lazy-initialized metrics property to `ServerRuntimeBase`:

**File: `core/src/main/kotlin/com/lightningkite/lightningserver/runtime/ServerRuntimeBase.kt`**

Add property:
```kotlin
/**
 * HTTP metrics for OpenTelemetry. Lazily initialized when first accessed.
 * Returns null if telemetry is not configured.
 */
val httpMetrics: HttpMetrics? by lazy {
    openTelemetry?.let { HttpMetrics(it.getMeter("com.lightningkite.lightningserver.http")) }
}
```

### Step 3: Update handleWithMetrics Function

Modify `implementationHelpers.kt` to record metrics:

```kotlin
context(serverRuntime: ServerRuntime)
private suspend inline fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(
    request: HttpRequest<PATH>,
): HttpResponse {
    val startTime = System.currentTimeMillis()

    return instrument(location.toString()) { span ->
        // Add useful HTTP attributes to the current span
        span?.setAttribute("http.method", request.path.method.toString())
        span?.setAttribute("http.route", location.toString())
        span?.setAttribute("http.target", "/" + request.path.pathSegments.toString())
        span?.setAttribute("http.scheme", request.protocol)
        span?.setAttribute("http.host", request.domain)
        span?.setAttribute("net.peer.ip", request.sourceIp)

        val response = this@handleWithMetrics.handle(request)

        span?.setAttribute("http.status_code", response.status.code.toLong())

        // Record metrics
        recordHttpMetrics(
            method = request.path.method.toString(),
            route = location.toString(),
            statusCode = response.status.code,
            durationMs = System.currentTimeMillis() - startTime
        )

        response
    }
}
```

### Step 4: Add Metrics Recording Helper

Add a helper function in `implementationHelpers.kt`:

```kotlin
/**
 * Records HTTP metrics for a completed request.
 */
context(serverRuntime: ServerRuntime)
private fun recordHttpMetrics(
    method: String,
    route: String,
    statusCode: Int,
    durationMs: Long,
    errorType: String? = null
) {
    val metrics = (serverRuntime as? ServerRuntimeBase)?.httpMetrics ?: return

    val attributes = Attributes.of(
        HttpMetrics.HTTP_METHOD, method,
        HttpMetrics.HTTP_ROUTE, route,
        HttpMetrics.HTTP_STATUS_CODE, statusCode.toLong()
    )

    val categoryAttributes = Attributes.of(
        HttpMetrics.HTTP_METHOD, method,
        HttpMetrics.HTTP_ROUTE, route,
        HttpMetrics.HTTP_STATUS_CATEGORY, HttpMetrics.statusCategory(statusCode)
    )

    // Record request duration
    metrics.requestDuration.record(durationMs, attributes)

    // Increment request count
    metrics.requestCount.add(1, attributes)

    // Increment category counter
    metrics.responsesByCategory.add(1, categoryAttributes)

    // Record errors (5xx)
    if (statusCode >= 500) {
        val errorAttributes = Attributes.builder()
            .put(HttpMetrics.HTTP_METHOD, method)
            .put(HttpMetrics.HTTP_ROUTE, route)
            .put(HttpMetrics.ERROR_TYPE, errorType ?: "server_error")
            .build()
        metrics.errors.add(1, errorAttributes)
    }
}
```

### Step 5: Update Exception Handler

Also record metrics in the exception handler path in the `handle()` function:

```kotlin
} catch (e: Exception) {
    try {
        this.logger.error(e) { "Exception in HTTP" }
        val response = instrument("exceptionHandler") {
            server.exceptionHandler.handle(request, e)
        }

        // Record metrics for exception path
        recordHttpMetrics(
            method = request.path.method.toString(),
            route = request.path.match.path.spec.toString(),
            statusCode = response.status.code,
            durationMs = System.currentTimeMillis() - startTime,
            errorType = e::class.simpleName
        )

        response
    } catch (e: Exception) {
        // Record metrics for catastrophic failure
        recordHttpMetrics(
            method = request.path.method.toString(),
            route = "unknown",
            statusCode = 500,
            durationMs = System.currentTimeMillis() - startTime,
            errorType = "unhandled_exception"
        )
        HttpResponse(status = HttpStatus.InternalServerError)
    }
}
```

### Step 6: Add WebSocket Metrics (Optional Extension)

Similar metrics can be added for WebSocket handlers:

**File: `core/src/main/kotlin/com/lightningkite/lightningserver/telemetry/WebSocketMetrics.kt`**

```kotlin
class WebSocketMetrics(meter: Meter) {
    val connections: LongCounter = meter.counterBuilder("ws.server.connections")
        .setDescription("Total WebSocket connections")
        .build()

    val disconnections: LongCounter = meter.counterBuilder("ws.server.disconnections")
        .setDescription("Total WebSocket disconnections")
        .build()

    val messagesReceived: LongCounter = meter.counterBuilder("ws.server.messages.received")
        .setDescription("WebSocket messages received from clients")
        .build()

    val messagesSent: LongCounter = meter.counterBuilder("ws.server.messages.sent")
        .setDescription("WebSocket messages sent to clients")
        .build()
}
```

## Metrics Summary

| Metric Name | Type | Description | Attributes |
|-------------|------|-------------|------------|
| `http.server.request.duration` | Histogram | Request duration in ms | method, route, status_code |
| `http.server.request.count` | Counter | Total request count | method, route, status_code |
| `http.server.response.status.category` | Counter | Responses by category | method, route, status_category |
| `http.server.errors` | Counter | Error count | method, route, error_type |

## Testing

Add unit tests to verify metrics are recorded:

```kotlin
@Test
fun `metrics recorded for successful requests`() {
    // Setup test server with mock meter
    // Make request
    // Verify requestCount incremented
    // Verify requestDuration recorded
    // Verify responsesByCategory incremented with "2xx"
}

@Test
fun `metrics recorded for error responses`() {
    // Setup endpoint that throws exception
    // Make request
    // Verify errors counter incremented
    // Verify responsesByCategory incremented with "5xx"
}
```

## Implementation Order

1. Create `HttpMetrics.kt` with metric instruments
2. Add `httpMetrics` property to `ServerRuntimeBase`
3. Add `recordHttpMetrics` helper function to `implementationHelpers.kt`
4. Update `handleWithMetrics` to track start time and record metrics
5. Update exception handler in `handle()` to record metrics
6. Add imports for `Attributes`, `AttributeKey`, etc.
7. Write unit tests
8. (Optional) Add WebSocket metrics

## Notes

- Metrics are only recorded if OpenTelemetry is configured (null-safe)
- Uses OpenTelemetry semantic conventions for attribute names
- Duration is recorded in milliseconds for reasonable precision
- Status category grouping (2xx, 3xx, etc.) allows for easy success/error rate dashboards
- Error type attribute allows drilling down into specific exception types
