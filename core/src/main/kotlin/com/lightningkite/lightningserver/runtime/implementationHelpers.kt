package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.*
import java.util.zip.GZIPOutputStream

// Pre-allocated TelemetryKey instances for custom WebSocket and task attributes (backend caches by equality).
private val wsRoute = TelemetryKey.OfString("ws.route")
private val wsFrameType = TelemetryKey.OfString("ws.frame.type")
private val wsFrameSize = TelemetryKey.OfLong("ws.frame.size")
private val wsSubscriptionTopic = TelemetryKey.OfString("ws.subscription.topic")
private val wsDisconnectCode = TelemetryKey.OfLong("ws.disconnect.code")
private val wsDisconnectReason = TelemetryKey.OfString("ws.disconnect.reason")
private val taskType = TelemetryKey.OfString("task.type")
private val taskRoute = TelemetryKey.OfString("task.route")
private val errorType = TelemetryKey.OfString("error.type")

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
public suspend fun ServerRuntime.handle(request: HttpRequest<PathSpec>): HttpResponse = instrumentHttpRequest(request) {
    var errorType: String? = null

    suspend fun handleError(e: Exception, label: String? = e::class.simpleName): HttpResponse {
        errorType = label
        return try {
            instrument("exceptionHandler") { server.exceptionHandler.handle(request, e) }
        } catch (_: Exception) {
            errorType = "unhandled_exception"
            HttpResponse(status = HttpStatus.InternalServerError)
        }
    }

    val response = try {
        server.compiledHttpInterceptors.intercept(request) { req ->
            // Access logging (with the resolved principal) is provided by the opt-in AccessLogInterceptor in
            // the auth module, not hardcoded here — so it can name the principal without core depending on auth.
            // Map handler/route/compression exceptions to responses in-place so the surrounding
            // interceptors (CORS, etc.) still post-process error responses.
            try {
                val result = try {
                    // Route resolution must live inside this try so that a RouteNotFoundException (e.g. a HEAD
                    // request with no HEAD handler, or a missing trailing slash) is caught below and recovered
                    // via the HEAD->GET fallback / slash-redirect logic rather than escaping as a bare 404.
                    @Suppress("UNCHECKED_CAST")
                    val handler = req.path.match.value as HttpHandler<PathSpec>
                    instrument("handler") {
                        // Per-handler request timeout (HttpHandler.timeout, default 30s), enforced at this single
                        // choke point shared by every engine instead of being duplicated (and high-risk) in each
                        // engine adapter. Cooperative cancellation: only interrupts at suspension points.
                        withTimeout(handler.timeout) {
                            handler.handle(req as HttpRequest<PathSpec>)
                        }
                    }
                } catch (notFound: RouteNotFoundException) {
                    when (req.path.method) {
                        HttpMethod.HEAD -> {
                            // OK, we'll do a get and remove the body.
                            val getRequest = req.copyWithNewPathType(path = req.path.copy(method = HttpMethod.GET))

                            @Suppress("UNCHECKED_CAST")
                            val headHandler = getRequest.path.match.value as HttpHandler<PathSpec>
                            val getResult = instrument("handler") {
                                withTimeout(headHandler.timeout) { headHandler.handle(getRequest) }
                            }
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
                if (result.body.data.size?.let { it < 256 } == true) return@intercept result

                val (newData, compressed) = when (val data = result.body.data) {
                    is Data.Sink -> {
                        Data.Sink { outSink ->
                            GZIPOutputStream(outSink.asOutputStream()).asSink().buffered().use { gzOut ->
                                data.write(gzOut)
                            }
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
                        val s = data.size
                        if (s?.let { it <= 1024 } == true) {
                            val og = data.bytes()
                            val gz = og.gzip()
                            if (gz.size < s)
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
            } catch (timeout: TimeoutCancellationException) {
                // A handler exceeded its HttpHandler.timeout. This is a server-side condition (the server
                // couldn't finish in time), so it maps to 503 Service Unavailable — NOT 408, which per
                // RFC 7231 means the client was too slow sending its request. Routed through the normal
                // exception handler so the error body is formatted consistently. (Other
                // CancellationExceptions — e.g. client disconnect — are handled by the generic catch below.)
                this.logger.warn { "Request to ${request.path} exceeded its handler timeout." }
                handleError(
                    HttpStatusException(
                        status = HttpStatus.ServiceUnavailable,
                        detail = "timeout",
                        message = "The request handler exceeded its timeout.",
                    ),
                    label = "timeout",
                )
            } catch (e: Exception) {
                this.logger.error(e) { "Exception in HTTP" }
                handleError(e)
            }
        }
    } catch (e: Exception) {
        // Safety net for exceptions thrown by an interceptor itself (outside the handler), which
        // never reach the in-chain handling above and so may lack interceptor-applied headers
        // (e.g. CORS) — acceptable for this rare failure mode.
        this.logger.error(e) { "Exception in HTTP interceptor chain" }
        handleError(e)
    }
    HttpInstrumentationResult(response, response.status.code, errorType)
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
        instrument("willConnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
        }) {
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
        instrument("didConnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
        }) {
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
        instrument("messageFromClient", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
            put(
                wsFrameType, when (frame) {
                    is WebSocketFrame.Text -> "text"
                    is WebSocketFrame.Binary -> "binary"
                }
            )
            put(
                wsFrameSize, when (frame) {
                    is WebSocketFrame.Text -> frame.content.length.toLong()
                    is WebSocketFrame.Binary -> frame.content.size.toLong()
                }
            )
        }) {
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
        instrument("messageFromSubscription", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
            put(wsSubscriptionTopic, topic.topic.location.toString())
        }) {
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
        instrument("disconnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
            put(wsDisconnectCode, reason.code.toLong())
            put(wsDisconnectReason, reason.name)
        }) {
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
    return instrument("task", TelemetryAttributes {
        put(taskType, "TASK")
        put(taskRoute, location.toString())
    }) {
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
    return instrument("schedule", TelemetryAttributes {
        put(taskType, "SCHEDULE")
        put(taskRoute, location.toString())
    }) {
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
    return instrument("startup", TelemetryAttributes {
        put(taskType, "STARTUP")
        put(taskRoute, location.toString())
    }) {
        execute()
    }
}

/**
 * Instruments a suspend block with the metrics backend, creating a named child span.
 *
 * All [attributes] are attached to the span at start. If telemetry is not configured the
 * call is a transparent no-op. Errors are recorded and re-thrown automatically by the backend.
 *
 * @param name Short operation name (e.g. "handler", "willConnect")
 * @param attributes Initial attributes attached to the span
 * @param action The code to run inside the span
 * @return The result of [action]
 */
context(runtime: ServerRuntime)
public suspend fun <T> instrument(
    name: String,
    attributes: TelemetryAttributes = TelemetryAttributes.empty,
    action: suspend () -> T,
): T = runtime.telemetryTrace(name, attributes) { action() }

/**
 * Carries the response value plus the HTTP status code and optional error class name that
 * [instrumentHttpRequest] enriches onto the span after the action completes.
 *
 * @param value The value returned from [instrumentHttpRequest] (the HttpResponse for plain
 *   requests, or a domain-specific wrapper such as a `BulkResponse` for handlers that re-dispatch).
 * @param statusCode The HTTP status code to record on the span.
 * @param errorType Optional simple class name of an exception the action handled (e.g. "timeout").
 */
public data class HttpInstrumentationResult<out T>(
    public val value: T,
    public val statusCode: Int,
    public val errorType: String? = null,
)

/**
 * Wraps an HTTP request flow with the standard root-span attributes and RED metrics.
 *
 * Resolves the route pattern from the request (falling back to the literal target if unmatched),
 * opens a span named "$method $route" with standard `http.*` attributes, runs the action, then
 * enriches the span with `http.status_code` and (when present) `error.type`.
 *
 * Used by [handle] for top-level requests and by bulk-endpoint handlers that re-dispatch inner
 * requests, giving each sub-request the same observability treatment as a normal request.
 */
context(runtime: ServerRuntime)
public suspend fun <T> instrumentHttpRequest(
    request: HttpRequest<*>,
    action: suspend () -> HttpInstrumentationResult<T>,
): T {
    val method = request.path.method.toString()
    val route = try {
        request.path.match.path.pathSpec.toString()
    } catch (_: Exception) {
        "/" + request.path.pathSegments.toString()
    }
    return runtime.telemetryTrace("$method $route", TelemetryAttributes {
        put(TelemetryKeys.Http.method, method)
        put(TelemetryKeys.Http.route, route)
        put(TelemetryKeys.Http.target, "/" + request.path.pathSegments.toString())
        put(TelemetryKeys.Http.scheme, request.protocol)
        put(TelemetryKeys.Http.host, request.domain)
        put(TelemetryKeys.Net.peerIp, request.sourceIp)
    }) { span ->
        val result = action()
        span.enrich(TelemetryAttributes {
            put(TelemetryKeys.Http.statusCode, result.statusCode.toLong())
            result.errorType?.let { put(errorType, it) }
        })
        result.value
    }
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