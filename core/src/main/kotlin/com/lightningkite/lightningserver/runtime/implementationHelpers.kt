package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.emptyTelemetryAttributes
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

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
 * 4. Handles exceptions and logs errors
 *
 * ## Automatic HEAD support
 * If no HEAD handler is registered, automatically transforms a GET request and strips the body.
 *
 * ## Trailing slash handling
 * If a route is not found, checks if an alternate version with/without trailing slash exists
 * and returns a redirect if found.
 *
 * Response compression is not part of routing; install
 * [com.lightningkite.lightningserver.compression.GzipInterceptor] if you want it.
 *
 * @param request The HTTP request to handle
 * @param executionId Identifies this run. Supplied by the engine, which is the only thing that knows
 *   whether the id was minted fresh or adopted from a trusted proxy; the rest of the initiator is
 *   derived from [request], so the two cannot disagree about what ran.
 * @return The HTTP response
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun Engine.handle(
    request: HttpRequest<PathSpec>,
    executionId: Uuid,
): HttpResponse = forExecution(
    Initiator.Http(executionId = executionId, endpoint = request.path)
).handleInExecution(request)

/**
 * Runs [body] as the whole of this execution, through every installed [ExecutionInterceptor].
 *
 * Sits inside the execution's telemetry span and outside the HTTP/WebSocket chains: an execution
 * interceptor wraps the whole of what ran, and what it does belongs to that execution's trace.
 */
@OptIn(InternalLightningServerApi::class)
private suspend fun <T> ServerRuntime.inExecution(body: suspend context(ServerRuntime) () -> T): T =
    server.compiledExecutionInterceptors.intercept(this, body)

private suspend fun ServerRuntime.handleInExecution(request: HttpRequest<PathSpec>): HttpResponse =
    instrumentHttpRequest(request) {
        var errorType: String? = null

        val response = try {
            inExecution {
                this@handleInExecution.server.compiledHttpConnectionInterceptors.intercept(request) { req ->
                    @Suppress("UNCHECKED_CAST")
                    val outcome = this@handleInExecution.dispatchLogicalRequest(req as HttpRequest<PathSpec>)
                    errorType = outcome.errorType
                    outcome.response
                }
            }
        } catch (e: Exception) {
            // Last-resort safety net. Exceptions thrown by an interceptor itself are now recovered
            // inside HttpInterceptor.interceptInstrumented, at the point each interceptor is invoked,
            // so outer interceptors (e.g. CORS) still get to post-process the resulting response. This
            // catch only fires when there are no interceptors installed (the compiled chain is
            // HttpInterceptor.NoOp, which bypasses interceptInstrumented) or some other exception
            // escapes the chain machinery itself — headers from would-be interceptors are absent here.
            this.logger.error(e) { "Exception in HTTP interceptor chain" }
            errorType = "unhandled_exception"
            try {
                instrument("exceptionHandler") { server.exceptionHandler.handle(request, e) }
            } catch (_: Exception) {
                HttpResponse(status = HttpStatus.InternalServerError)
            }
        }
        HttpInstrumentationResult(response, response.status.code, errorType)
    }

/**
 * Handles one logical sub-request dispatched by a multiplexed request such as `/meta/bulk`.
 *
 * A multiplexed endpoint must route its sub-requests through this rather than invoking the matched
 * handler directly, or the sub-requests bypass every [HttpLogicalInterceptor] — access logging,
 * auditing and rate limiting among them — and execute unobserved. [HttpConnectionInterceptor]s are
 * deliberately not re-run: they already ran for the physical request that carried this one.
 *
 * The sub-request's initiator is derived here rather than supplied, so it cannot be got wrong: it
 * gets its own execution id, parented to the request that carried it. A sub-request that reused the
 * outer id would make the two indistinguishable in the audit trail; one with no parent would be
 * unattributable to the request that actually carried it.
 *
 * @param request must be derived with [HttpRequest.subRequest].
 * @throws IllegalArgumentException if this is not running inside an HTTP execution, which means
 *   there is no request for the sub-request to be a sub-request *of*.
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun ServerRuntime.handleSubRequest(request: HttpRequest<PathSpec>): HttpResponse {
    val outer = initiator
    require(outer is Initiator.Http) {
        "Sub-requests may only be dispatched from inside an HTTP execution, so that they can be " +
            "parented to the request that carried them; ${request.path} was dispatched from $outer."
    }
    val runtime = forExecution(outer.subRequest(request.path))
    return with(runtime) {
        instrumentHttpRequest(request) {
            val outcome = runtime.inExecution { runtime.dispatchLogicalRequest(request) }
            HttpInstrumentationResult(outcome.response, outcome.response.status.code, outcome.errorType)
        }
    }
}

/** The response for one logical request, plus the error label telemetry needs for it. */
private class LogicalRequestOutcome(val response: HttpResponse, val errorType: String?)

/**
 * Runs the logical-request interceptor chain, resolves the route, and invokes the handler.
 *
 * This is the single choke point every logical request passes through, whether it arrived directly
 * from a client or was dispatched inside a multiplexed one.
 */
private suspend fun ServerRuntime.dispatchLogicalRequest(request: HttpRequest<PathSpec>): LogicalRequestOutcome {
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

    val response = server.compiledHttpLogicalInterceptors.intercept(request) { req ->
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
                        if (req.path.pathSegments.isNotEmpty()) {
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
            result
        } catch (timeout: TimeoutCancellationException) {
            // A handler exceeded its HttpHandler.timeout. This is a server-side condition (the server
            // couldn't finish in time), so it maps to 503 Service Unavailable — NOT 408, which per
            // RFC 7231 means the client was too slow sending its request. Routed through the normal
            // exception handler so the error body is formatted consistently. (Other
            // CancellationExceptions — e.g. client disconnect — are handled by the generic catch below.)
            this.logger.warn { "Request to ${req.path} exceeded its handler timeout." }
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
    return LogicalRequestOutcome(response, errorType)
}


/**
 * Wraps a WebSocket willConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator The socket's connect initiator, minted by the engine and kept with the connection
 *   so that every later phase can derive its own from it with [phase].
 * @param request The WebSocket connection request
 * @return The connection storage state
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Initiator.WebSocket,
    request: WebSocketConnectRequest<PATH>,
): STORAGE {
    val runtime = engine.forExecution(initiator)
    return with(runtime) {
        instrument("willConnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
        }) {
            runtime.inExecution { willConnect(request) }
        }
    }
}

/**
 * Wraps a WebSocket didConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The established WebSocket connection
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Initiator.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
) {
    val runtime = engine.forExecution(initiator)
    return with(runtime) {
        instrument("didConnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
        }) {
            runtime.inExecution { didConnect(connection) }
        }
    }
}

/**
 * Wraps a WebSocket messageFromClient handler invocation with telemetry metrics.
 *
 * Records the frame type (text/binary) and size in telemetry.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The WebSocket connection
 * @param frame The frame received from the client
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Initiator.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
) {
    val runtime = engine.forExecution(initiator)
    return with(runtime) {
        instrument("messageFromClient", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
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
            runtime.inExecution { messageFromClient(connection, frame) }
        }
    }
}

/**
 * Wraps a WebSocket messageFromSubscription handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The WebSocket connection
 * @param topic The subscription message received
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Initiator.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
) {
    val runtime = engine.forExecution(initiator)
    return with(runtime) {
        instrument("messageFromSubscription", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
            put(wsSubscriptionTopic, topic.topic.location.toString())
        }) {
            runtime.inExecution { messageFromSubscription(connection, topic) }
        }
    }
}

/**
 * Wraps a WebSocket disconnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The WebSocket connection being closed
 * @param reason The close reason and code
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Initiator.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
) {
    val runtime = engine.forExecution(initiator)
    return with(runtime) {
        instrument("disconnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
            put(wsDisconnectCode, reason.code.toLong())
            put(wsDisconnectReason, reason.name)
        }) {
            runtime.inExecution { disconnect(connection, reason) }
        }
    }
}

/**
 * The location a task-like execution is registered under, as an initiator can hold it.
 *
 * Tasks, schedules, startup and pre-deploy tasks are all registered under all-constant paths, so the
 * segments are the whole of their location.
 */
private fun PathSpec0.asPathSegments(): PathSegments = PathSegments.parse(toString())

/**
 * The facts that keep one kind of task-like execution distinguishable from another: how its span is
 * named and labelled, and which [Initiator] it is attributed to. Everything else about running one is
 * identical, and lives in [executeTaskLike].
 */
@OptIn(InternalLightningServerApi::class)
private enum class TaskKind(
    val label: String,
    val telemetryType: String,
    val initiator: (executionId: Uuid, causedBy: Uuid?, rootExecutionId: Uuid, location: PathSegments) -> Initiator,
) {
    Task("task", "TASK", { id, by, root, at -> Initiator.Task(id, by, root, at) }),
    Schedule("schedule", "SCHEDULE", { id, by, root, at -> Initiator.Schedule(id, by, root, at) }),
    Startup("startup", "STARTUP", { id, by, root, at -> Initiator.Startup(id, by, root, at) }),
    PreDeploy("predeploy", "PREDEPLOY", { id, by, root, at -> Initiator.PreDeploy(id, by, root, at) }),
}

/**
 * Mints the execution for one task-like run, instruments it, and runs [body] inside it.
 *
 * The four kinds differ only in the three facts [TaskKind] holds and in what they invoke, so this is
 * the whole of what "run a task" means; the public entry points below exist to name the receiver each
 * kind is invoked on.
 *
 * @param cause The execution that launched this one, as the engine received it — over a queue for a
 *   serverless engine, in memory for a single-process one. Only a queued [Task] can have one; the
 *   other three kinds are started by the server itself and pass null.
 */
@OptIn(InternalLightningServerApi::class)
private suspend fun Engine.executeTaskLike(
    kind: TaskKind,
    location: PathSpec0,
    cause: ExecutionCause?,
    body: suspend context(ServerRuntime) () -> Unit,
) {
    val executionId = Uuid.random()
    val runtime = forExecution(
        kind.initiator(
            executionId,
            cause?.causedBy,
            // With no launcher this execution heads its own causal chain, so it is its own root.
            cause?.rootExecutionId ?: executionId,
            location.asPathSegments(),
        )
    )
    // Span name includes the location so traces distinguish one task from another, the same way
    // HTTP root spans are named "$method $route". Locations are a fixed, static set, so this is
    // low-cardinality.
    return with(runtime) {
        instrument("${kind.label} $location", TelemetryAttributes {
            put(taskType, kind.telemetryType)
            put(taskRoute, location.toString())
        }) {
            runtime.inExecution(body)
        }
    }
}

/**
 * Executes a task with telemetry metrics.
 *
 * @param location The path specification for this task
 * @param input The input parameter for the task
 * @param cause The execution that launched this task, or null when nothing launched it, such as a
 *   manual invocation.
 */
context(engine: Engine)
public suspend fun <T> Task<T>.executeWithMetrics(location: PathSpec0, input: T, cause: ExecutionCause?): Unit =
    engine.executeTaskLike(TaskKind.Task, location, cause) { executeInline(input) }

/**
 * Executes a scheduled task with telemetry metrics.
 *
 * @param location The path specification for this scheduled task
 */
context(engine: Engine)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0): Unit =
    engine.executeTaskLike(TaskKind.Schedule, location, cause = null) { execute() }

/**
 * Executes a startup task with telemetry metrics.
 *
 * @param location The path specification for this startup task
 */
context(engine: Engine)
public suspend fun StartupTask.executeWithMetrics(location: PathSpec0): Unit =
    engine.executeTaskLike(TaskKind.Startup, location, cause = null) { execute() }

/**
 * Executes a pre-deploy task with telemetry metrics.
 *
 * @param location The path specification for this pre-deploy task
 */
context(engine: Engine)
public suspend fun PreDeployTask.executeWithMetrics(location: PathSpec0): Unit =
    engine.executeTaskLike(TaskKind.PreDeploy, location, cause = null) { execute() }

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
context(runtime: Engine)
public suspend fun <T> instrument(
    name: String,
    attributes: TelemetryAttributes = emptyTelemetryAttributes(),
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
context(runtime: Engine)
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