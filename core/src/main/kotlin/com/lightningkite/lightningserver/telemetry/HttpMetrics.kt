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
 * - http.server.request.duration: Duration of HTTP requests (histogram)
 * - http.server.request.count: Total count of HTTP requests (counter)
 * - http.server.response.status.category: Count by status code category (counter)
 * - http.server.errors: Count of server errors (counter)
 *
 * Usage:
 * ```kotlin
 * val metrics = HttpMetrics(meter)
 * metrics.record(method = "GET", route = "/api/users", statusCode = 200, durationMs = 45)
 * ```
 */
public class HttpMetrics(meter: Meter) {

    /**
     * Histogram of HTTP request durations in milliseconds.
     * Attributes: http.method, http.route, http.status_code
     */
    public val requestDuration: LongHistogram = meter.histogramBuilder("http.server.request.duration")
        .setDescription("Duration of HTTP server requests in milliseconds")
        .setUnit("ms")
        .ofLongs()
        .build()

    /**
     * Counter for total HTTP requests.
     * Attributes: http.method, http.route, http.status_code
     */
    public val requestCount: LongCounter = meter.counterBuilder("http.server.request.count")
        .setDescription("Total count of HTTP server requests")
        .setUnit("{request}")
        .build()

    /**
     * Counter for HTTP responses by status category.
     * Attributes: http.method, http.route, http.status_category (1xx, 2xx, 3xx, 4xx, 5xx)
     */
    public val responsesByCategory: LongCounter = meter.counterBuilder("http.server.response.status.category")
        .setDescription("Count of HTTP responses by status category")
        .setUnit("{response}")
        .build()

    /**
     * Counter for errors (5xx responses).
     * Attributes: http.method, http.route, error.type
     */
    public val errors: LongCounter = meter.counterBuilder("http.server.errors")
        .setDescription("Count of HTTP server errors (5xx responses)")
        .setUnit("{error}")
        .build()

    /**
     * Records metrics for a completed HTTP request.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param route The matched route pattern (e.g., "/api/users/{id}")
     * @param statusCode HTTP response status code
     * @param durationMs Request duration in milliseconds
     * @param errorType Optional error type for 5xx responses (e.g., exception class name)
     */
    public fun record(
        method: String,
        route: String,
        statusCode: Int,
        durationMs: Long,
        errorType: String? = null
    ) {
        val attributes = Attributes.of(
            HTTP_METHOD, method,
            HTTP_ROUTE, route,
            HTTP_STATUS_CODE, statusCode.toLong()
        )

        val categoryAttributes = Attributes.of(
            HTTP_METHOD, method,
            HTTP_ROUTE, route,
            HTTP_STATUS_CATEGORY, statusCategory(statusCode)
        )

        // Record request duration
        requestDuration.record(durationMs, attributes)

        // Increment request count
        requestCount.add(1, attributes)

        // Increment category counter
        responsesByCategory.add(1, categoryAttributes)

        // Record errors (5xx)
        if (statusCode >= 500) {
            val errorAttributes = Attributes.builder()
                .put(HTTP_METHOD, method)
                .put(HTTP_ROUTE, route)
                .put(ERROR_TYPE, errorType ?: "server_error")
                .build()
            errors.add(1, errorAttributes)
        }
    }

    public companion object {
        // Attribute keys following OpenTelemetry semantic conventions
        public val HTTP_METHOD: AttributeKey<String> = AttributeKey.stringKey("http.method")
        public val HTTP_ROUTE: AttributeKey<String> = AttributeKey.stringKey("http.route")
        public val HTTP_STATUS_CODE: AttributeKey<Long> = AttributeKey.longKey("http.status_code")
        public val HTTP_STATUS_CATEGORY: AttributeKey<String> = AttributeKey.stringKey("http.status_category")
        public val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")

        /**
         * Converts HTTP status code to category string (1xx, 2xx, 3xx, 4xx, 5xx).
         */
        public fun statusCategory(code: Int): String = when (code) {
            in 100..199 -> "1xx"
            in 200..299 -> "2xx"
            in 300..399 -> "3xx"
            in 400..499 -> "4xx"
            in 500..599 -> "5xx"
            else -> "unknown"
        }
    }
}
