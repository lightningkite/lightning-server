package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

// Pre-allocated TelemetryKey instance (backend caches by equality).
private val errorType = TelemetryKey.OfString("error.type")

/**
 * Runs this handler for [request] as a new execution caused by the current one, exactly as a routed
 * sub-request would be but without routing.
 *
 * Like any sub-request, it runs through the server's execution interceptors but not its HTTP
 * interceptors, which only see requests the engine received.
 */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(
    request: HttpRequest<PATH>,
): HttpResponse = runtime.executeHttp(
    request,
    @OptIn(InternalLightningServerApi::class)
    Execution.Http(id = Execution.ID.generate(), parent = runtime.execution, endpoint = request.path),
    HttpInterceptor.NoOp,
) { req ->
    // Claude: `this@` because a bare `handle` resolves to ServerRuntime.handle, which routes the request.
    @OptIn(OverrideOnly::class)
    withHandlerTimeout(this@handleWithMetrics.timeout) { this@handleWithMetrics.handle(req) }
}

/**
 * Handles an HTTP request through the server's routing and middleware system.
 *
 * This is the core request handler that:
 * 1. Routes the request to the appropriate handler
 * 2. Handles special HTTP methods (HEAD, OPTIONS) automatically
 * 3. Provides trailing slash redirect logic when routes differ only by trailing slash
 * 4. Handles exceptions and logs errors
 *
 * The request runs through the server's execution interceptors and then its HTTP interceptors. It is
 * the only kind of request the HTTP interceptors see.
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
@EngineApi
public suspend fun Engine.handleRoot(
    request: HttpRequest<*>,
    executionId: Execution.ID,
): HttpResponse =
    // On the process engine, so a root never runs nested inside whatever runtime happens to be in scope.
    processEngine.executeHttp(
        request,
        @OptIn(InternalLightningServerApi::class)
        Execution.Http(id = executionId, endpoint = request.path),
        server.compiledHttpInterceptors,
        ServerRuntime::routeHttpRequest
    )

/**
 * Routes [request] as a sub-request of the current execution, such as one part of a `/meta/bulk` call.
 *
 * The sub-request runs through the server's execution interceptors but not its HTTP interceptors, and
 * exceptions are turned into the returned response.
 */
public suspend fun ServerRuntime.handle(
    request: HttpRequest<*>,
    executionId: Execution.ID = Execution.ID.generate()
): HttpResponse = executeHttp(
    request,
    @OptIn(InternalLightningServerApi::class)
    Execution.Http(id = executionId, parent = execution, endpoint = request.path),
    HttpInterceptor.NoOp,
    ServerRuntime::routeHttpRequest
)

/**
 * Runs [action] for [request] as a new [Execution.Http] caused by the current one, under this handler's
 * timeout and through the server's execution interceptors.
 *
 * Unlike [handleWithMetrics], nothing here deals in [HttpResponse]: [action]'s result is returned and its
 * exceptions are thrown as they are. A timeout is thrown as a 503 [HttpStatusException].
 */
@InternalLightningServerApi
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, T> HttpHandler<PATH>.executeWithMetrics(
    request: HttpRequest<PATH>,
    action: suspend ServerRuntime.(HttpRequest<PATH>) -> T,
): T = runtime.execute(
    request.operationName(),
    Invocation.Http(
        Execution.Http(id = Execution.ID.generate(), parent = runtime.execution, endpoint = request.path),
        request,
    ),
    request.telemetryAttributes(),
) {
    withHandlerTimeout(this@executeWithMetrics.timeout) { action(request) }
}


// INTERNAL IMPLEMENTATION STUFF

context(engine: Engine)
internal fun HttpRequest<*>.operationName(): String {
    // The operation name becomes a metric label.  An unmatched request's path and method are whatever
    // the client sent (scanners send thousands), so they must not appear in it.
    val route = path.tryResolve()?.pathSpec ?: return "unmatched"
    return "${path.method} $route"
}

context(engine: Engine)
internal fun HttpRequest<*>.telemetryAttributes() = TelemetryAttributes {
    put(TelemetryKeys.Http.method, path.method.toString())
    path.tryResolve()?.let { put(TelemetryKeys.Http.route, it.pathSpec.toString()) }
    put(TelemetryKeys.Http.target, "/" + path.pathSegments.toString())
    put(TelemetryKeys.Http.scheme, protocol)
    put(TelemetryKeys.Http.host, domain)
    put(TelemetryKeys.Net.peerIp, sourceIp)
}

// Claude: an overrun handler is the server failing to finish, so 503, not 408 (the client sending too slowly).
private suspend inline fun <T> ServerRuntime.withHandlerTimeout(timeout: Duration, crossinline action: suspend () -> T): T =
    try {
        instrument("handler") { withTimeout(timeout) { action() } }
    } catch (_: TimeoutCancellationException) {
        logger.warn { "A request handler exceeded its timeout of $timeout." }
        throw HttpStatusException(
            status = HttpStatus.ServiceUnavailable,
            detail = "timeout",
            message = "The request handler exceeded its timeout.",
        )
    }

/**
 * Runs [dispatch] for [request] as [execution], inside [interceptors], turning any exception into a
 * response. [interceptors] is the server's HTTP chain for a request the engine received, and
 * [HttpInterceptor.NoOp] for one dispatched inside another execution.
 */
@OptIn(InternalLightningServerApi::class)
private suspend fun <PATH : PathSpec> Engine.executeHttp(
    request: HttpRequest<PATH>,
    execution: Execution.Http,
    interceptors: HttpInterceptor,
    dispatch: suspend ServerRuntime.(HttpRequest<PATH>) -> HttpResponse,
): HttpResponse = try {
    execute(request.operationName(), Invocation.Http(execution, request), request.telemetryAttributes()) { trace ->
        var handledError: String? = null
        suspend fun handleError(e: Exception): HttpResponse {
            handledError = e::class.simpleName
            return try {
                instrument("exceptionHandler") { serverRuntime.server.exceptionHandler.handle(request, e) }
            } catch (_: Exception) {
                handledError = "unhandled_exception"
                HttpResponse(status = HttpStatus.InternalServerError)
            }
        }

        val response = try {
            interceptors.intercept(request) { req ->
                // Map handler and routing exceptions to responses in-place so the surrounding
                // interceptors (CORS, etc.) still post-process error responses.
                try {
                    dispatch(req)
                } catch (e: Exception) {
                    // HttpStatusExceptions are expected client-facing outcomes (400, 401, 404, ...), not server faults.
                    if (e is HttpStatusException) serverRuntime.logger.debug(e) { "HttpStatusException in HTTP" }
                    else serverRuntime.logger.error(e) { "Exception in HTTP" }
                    handleError(e)
                }
            }
        } catch (e: Exception) {
            if (e is HttpStatusException) logger.debug(e) { "HttpStatusException in HTTP interceptor chain" }
            else logger.error(e) { "Exception in HTTP interceptor chain" }
            handleError(e)
        }

        trace.enrich(TelemetryAttributes {
            put(TelemetryKeys.Http.statusCode, response.status.code.toLong())
            handledError?.let { put(errorType, it) }
        })
        response
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // Claude: the execution interceptors wrap the error handling above, so their own failures land here.
    logger.error(e) { "Exception in execution interceptor chain" }
    HttpResponse(status = HttpStatus.InternalServerError)
}


@OptIn(OverrideOnly::class)
private suspend fun <PATH : PathSpec> ServerRuntime.routeHttpRequest(req: HttpRequest<PATH>): HttpResponse = try {
    // Route resolution must live inside this try so that a RouteNotFoundException (e.g. a HEAD
    // request with no HEAD handler, or a missing trailing slash) is caught below and recovered
    // via the HEAD->GET fallback / slash-redirect logic rather than escaping as a bare 404.
    val handler = req.path.resolve().value
    withHandlerTimeout(handler.timeout) { handler.handle(req) }
} catch (notFound: RouteNotFoundException) {
    when (req.path.method) {
        HttpMethod.HEAD -> {
            // OK, we'll do a get and remove the body. The GET is routed afresh, so it may not be a PATH.
            val getRequest = req.copyWithNewPathType(path = RawHttpEndpoint(req.path.pathSegments, HttpMethod.GET))
            val handler = getRequest.path.resolve().value
            val getResult = withHandlerTimeout(handler.timeout) { handler.handle(getRequest) }
            getResult.copy(
                body = null,
                status = if (getResult.status.success) HttpStatus.NoContent else getResult.status,
            )
        }

        else -> {
            logger.debug {
                "Not found: ${req.path.pathSegments.segments.map { "'$it'" }}, looking for slashes"
            }
            if (req.path.pathSegments.isNotEmpty()) {
                // Let's see if they just got their ending slash wrong.
                val altSlashEndpoint = RawHttpEndpoint(
                    req.path.pathSegments.segments
                        .let { if (it.lastOrNull() == "") it.dropLast(1) else it + "" }
                        .let(::PathSegments),
                    req.path.method,
                )
                if (altSlashEndpoint.tryResolve() != null)
                    HttpResponse.pathMoved(to = "/" + altSlashEndpoint.pathSegments.toString())
                else
                    throw notFound
            } else throw notFound
        }
    }
}

/*
 * TODO: API Recommendations
 *
 * 2. The automatic HEAD support silently falls back to GET. This could be surprising and cause
 *    unnecessary computation for expensive GET handlers. Document this behavior clearly or add
 *    a way to opt out.
 *
 * 3. Trailing slash redirect uses PathSegments.toString() which may not preserve query parameters
 *    or fragments. Verify this behavior and document it.
 *
 * 4. The exception handler itself can throw exceptions, but those are caught and return a generic
 *    500 with no logging. The error is silently swallowed.
 */
