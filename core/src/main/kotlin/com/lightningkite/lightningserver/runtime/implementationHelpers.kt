package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathMoved
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.telemetry.use
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.otel.get
import io.opentelemetry.api.trace.Span
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.util.zip.GZIPOutputStream

/**
 * Handles an HTTP request through the server's routing and middleware system.
 *
 * This is the core request handler that:
 * 1. Routes the request to the appropriate handler
 * 2. Handles special HTTP methods (HEAD, OPTIONS) automatically
 * 3. Provides trailing slash redirect logic when routes differ only by trailing slash
 * 4. Applies GZIP compression when appropriate
 * 5. Handles exceptions and logs errors
 *
 * ## Automatic HEAD support
 * If no HEAD handler is registered, automatically transforms a GET request and strips the body.
 *
 * ## Trailing slash handling
 * If a route is not found, checks if an alternate version with/without trailing slash exists
 * and returns a redirect if found.
 *
 * ## GZIP compression
 * Automatically compresses responses when:
 * - Client sends Accept-Encoding: gzip header
 * - Response body is at least 256 bytes
 * - Content type is not already compressed (images, videos, fonts, archives, etc.)
 *
 * For payloads 256-1024 bytes, only compresses if compression reduces size.
 * For larger payloads, always compresses.
 *
 * @param request The HTTP request to handle
 * @return The HTTP response, potentially compressed
 */
public suspend fun ServerRuntime.handle(request: HttpRequest<PathSpec>): HttpResponse {
    return try {
        server.compiledHttpInterceptors.intercept(request) { req ->
            this.logger.info { "${request.path} accessed by ${request.sourceIp}" }
            val result = try {
                @Suppress("UNCHECKED_CAST")
                (req.path.match.value as HttpHandler<PathSpec>).handleWithMetrics(req as HttpRequest<PathSpec>)
            } catch (notFound: RouteNotFoundException) {
                when (req.path.method) {
                    HttpMethod.HEAD -> {
                        // OK, we'll do a get and remove the body.
                        val getRequest = req.copyWithNewPathType(path = req.path.copy(method = HttpMethod.GET))

                        @Suppress("UNCHECKED_CAST")
                        val getResult =
                            (getRequest.path.match.value as HttpHandler<PathSpec>).handleWithMetrics(getRequest)
                        getResult.copy(
                            body = null,
                            status = if (getResult.status.success) HttpStatus.NoContent else getResult.status,
                        )
                    }

                    else -> {
                        this.logger.debug {
                            "Not found: ${req.path.pathSegments.segments.map { "'$it'" }}, looking for slashes"
                        }
                        if (request.path.pathSegments.isNotEmpty()) {
                            // Let's see if they just got their ending slash wrong.
                            val altSlashEndpoint = req.path.copy(pathSegments = req.path.pathSegments.segments.let {
                                if (it.lastOrNull() == "") it.dropLast(1) else it + ""
                            }.let(::PathSegments))
                            try {
                                altSlashEndpoint.match
                                HttpResponse.pathMoved(to = "/" + altSlashEndpoint.pathSegments.toString())
                            } catch (_: RouteNotFoundException) {
                                throw notFound
                            }
                        } else throw notFound
                    }
                }
            }
            if (result.body == null || request.headers[HttpHeader.AcceptEncoding] == null) return@intercept result

            val acceptedEncodings = request.headers.getMany(HttpHeader.AcceptEncoding)
            if (acceptedEncodings.isEmpty()) return@intercept result

            val accepts = acceptedEncodings
                .map { it.root.lowercase().substringBefore(';').trim() }

            // Accept-Encoding negotiation (gzip only for now)
            if (!accepts.contains("gzip")) return@intercept result

            // Content-Type denylist (skip already-compressed types)
            if (result.body.mediaType.type in setOf("image", "audio", "video") ||
                (result.body.mediaType.type == "application" &&
                        result.body.mediaType.subtype in
                        setOf("zip", "gzip", "x-gzip", "x-7z-compressed", "x-bzip2", "x-tar", "pdf")
                        ) ||
                (result.body.mediaType.type == "font" && result.body.mediaType.subtype in setOf("woff", "woff2"))
            ) return@intercept result

            // Lower compress limit. Either not worth the effort, or likely will inflate a little.
            if (result.body.data.size != -1L && result.body.data.size < 256 /*256 bytes*/) return@intercept result

            val (newData, compressed) = when (val data = result.body.data) {
                is Data.Sink -> {
                    Data.Sink { outSink ->
                        data.write(GZIPOutputStream(outSink.asOutputStream()).asSink().buffered())
                    } to true
                }

                is Data.Source -> {
                    Data.Sink { outSink ->
                        GZIPOutputStream(outSink.asOutputStream()).asSink().buffered().use { gzOut ->
                            data.write(gzOut)
                        }
                    } to true
                }

                else -> {
                    // 1024 Grey area. It likely will compress fine, but if not send the original
                    if (data.size <= 1024 /*1 kibibyte*/) {
                        val og = data.bytes()
                        val gz = og.gzip()
                        if (gz.size < data.size)
                            Data.Bytes(gz) to true
                        else
                            Data.Bytes(og) to false
                    } else
                        Data.Bytes(data.bytes().gzip()) to true
                }
            }
            result.copy(
                headers = if (compressed) result.headers.copy {
                    add(HttpHeader.ContentEncoding, "gzip")
                } else result.headers,
                body = TypedData(newData, result.body.mediaType)
            )
        }
    } catch (e: Exception) {
        try {
            this.logger.error(e) { "Exception in HTTP" }
            instrument("exceptionHandler") {
                server.exceptionHandler.handle(
                    request,
                    e
                )
            }
        } catch (e: Exception) {
            HttpResponse(status = HttpStatus.InternalServerError)
        }
    }
}

/**
 * Wraps an HTTP handler invocation with telemetry metrics.
 *
 * Adds standard HTTP attributes to the telemetry span including method, route, status code, etc.
 *
 * @param request The HTTP request to handle
 * @return The HTTP response from the handler
 */
context(serverRuntime: ServerRuntime) private suspend inline fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(
    request: HttpRequest<PATH>,
): HttpResponse {
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
        response
    }
}


/**
 * Wraps a WebSocket willConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param serverRuntime The server runtime context
 * @param request The WebSocket connection request
 * @return The connection storage state
 */
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(
    location: PATH,
    serverRuntime: ServerRuntime,
    request: WebSocketConnectRequest<PATH>,
): STORAGE {
    return with(serverRuntime) {
        instrument("WEBSOCKET.WILLCONNECT $location") { span ->
            span?.setAttribute("ws.event", "willConnect")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            willConnect(request)
        }
    }
}

/**
 * Wraps a WebSocket didConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The established WebSocket connection
 */
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
) {
    return with(connection) {
        instrument("WEBSOCKET.DIDCONNECT $location") { span ->
            span?.setAttribute("ws.event", "didConnect")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            didConnect()
        }
    }
}

/**
 * Wraps a WebSocket messageFromClient handler invocation with telemetry metrics.
 *
 * Records the frame type (text/binary) and size in telemetry.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The WebSocket connection
 * @param frame The frame received from the client
 */
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
) {
    return with(connection) {
        instrument("WEBSOCKET.MESSAGE $location") { span ->
            span?.setAttribute("ws.event", "messageFromClient")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            span?.setAttribute(
                "ws.frame.type", when (frame) {
                    is WebSocketFrame.Text -> "text"
                    is WebSocketFrame.Binary -> "binary"
                }
            )
            span?.setAttribute(
                "ws.frame.size", when (frame) {
                    is WebSocketFrame.Text -> frame.content.length.toLong()
                    is WebSocketFrame.Binary -> frame.content.size.toLong()
                }
            )
            messageFromClient(frame)
        }
    }
}

/**
 * Wraps a WebSocket messageFromSubscription handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The WebSocket connection
 * @param topic The subscription message received
 */
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
) {
    return with(connection) {
        instrument("WEBSOCKET.SUBSCRIPTION $location") { span ->
            span?.setAttribute("ws.event", "messageFromSubscription")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            span?.setAttribute("ws.subscription.topic", topic.topic.location.toString())
            messageFromSubscription(topic)
        }
    }
}

/**
 * Wraps a WebSocket disconnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The WebSocket connection being closed
 * @param reason The close reason and code
 */
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
) {
    return with(connection) {
        instrument("WEBSOCKET.DISCONNECT $location") { span ->
            span?.setAttribute("ws.event", "disconnect")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            span?.setAttribute("ws.disconnect.code", reason.code.toLong())
            span?.setAttribute("ws.disconnect.reason", reason.name)
            disconnect(reason)
        }
    }
}

/**
 * Executes a task with telemetry metrics.
 *
 * @param location The path specification for this task
 * @param input The input parameter for the task
 */
context(serverRuntime: ServerRuntime)
public suspend fun <T> Task<T>.executeWithMetrics(location: PathSpec0, input: T) {
    return instrument("TASK $location") { span ->
        span?.setAttribute("task.type", "TASK")
        span?.setAttribute("task.route", location.toString())
        with(serverRuntime) {
            this@executeWithMetrics.executeInline(input)
        }
    }
}

/**
 * Executes a scheduled task with telemetry metrics.
 *
 * @param location The path specification for this scheduled task
 */
context(serverRuntime: ServerRuntime)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0) {
    return instrument("SCHEDULE $location") { span ->
        span?.setAttribute("task.type", "SCHEDULE")
        span?.setAttribute("task.route", location.toString())
        with(serverRuntime) {
            this@executeWithMetrics.execute()
        }
    }
}

/**
 * Executes a startup task with telemetry metrics.
 *
 * @param location The path specification for this startup task
 */
context(serverRuntime: ServerRuntime)
public suspend fun StartupTask.executeWithMetrics(location: PathSpec0) {
    return instrument("STARTUP $location") { span ->
        span?.setAttribute("task.type", "STARTUP")
        span?.setAttribute("task.route", location.toString())
        execute()
    }
}

/**
 * Instruments a code block with OpenTelemetry tracing.
 *
 * If telemetry is enabled, creates a span with the given name and executes the action within it.
 * If an exception occurs, records it in the telemetry before re-throwing.
 * If telemetry is not enabled, executes the action directly without overhead.
 *
 * @param name The name of the telemetry span
 * @param action The code block to execute, receiving an optional Span
 * @return The result of the action
 */
context(runtime: ServerRuntime)
public suspend inline fun <T> instrument(name: String, crossinline action: suspend (Span?) -> T): T {
    val tel = runtime.openTelemetry?.get("com.lightningkite.lightningserver")
    return if (tel != null) tel.spanBuilder(name).use {
        try {
            action(it)
        } catch (t: Throwable) {
            tel.error("Context $name failed", t)
            throw t
        }
    } else action(null)
}

/*
 * TODO: API Recommendations for implementationHelpers.kt
 *
 * 1. The handle() function is extremely complex (160+ lines) with multiple responsibilities:
 *    routing, compression, HEAD/OPTIONS handling, trailing slash redirects, exception handling.
 *    Consider breaking into smaller, testable functions.
 *
 * 2. GZIP compression logic has magic numbers (256 bytes, 1024 bytes) without constants.
 *    Define these as named constants with documentation explaining the thresholds.
 *
 * 3. The compression denylist (images, videos, fonts, archives) is hardcoded. Consider making
 *    this configurable via settings for applications with different compression needs.
 *
 * 4. The automatic HEAD support silently falls back to GET. This could be surprising and cause
 *    unnecessary computation for expensive GET handlers. Document this behavior clearly or add
 *    a way to opt out.
 *
 * 5. Trailing slash redirect uses PathSegments.toString() which may not preserve query parameters
 *    or fragments. Verify this behavior and document it.
 *
 * 6. The exception handler itself can throw exceptions (catch block line 152-163), but those are
 *    caught and return a generic 500 with no logging. The error is silently swallowed.
 *
 * 7. Compression for Data.Sink and Data.Source always returns `true` for compressed flag even if
 *    GZIP might fail or produce larger output. Consider checking actual compression ratio.
 *
 * 8. The telemetry span names use different formats: "http.route" vs "WEBSOCKET.WILLCONNECT".
 *    Standardize naming conventions for consistency.
 *
 * 9. The *WithMetrics functions are public but marked as internal in some cases with @PublishedApi.
 *    Clarify the intended visibility and usage patterns.
 *
 * 10. UnregisteredException provides minimal context - just "Item $item is unregistered".
 *     Consider adding which server it was looked up in, or suggestions for common mistakes.
 */