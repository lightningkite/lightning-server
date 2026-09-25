package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.route
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

// Pre-allocated TelemetryKey instance (backend caches by equality).
private val errorType = TelemetryKey.OfString("error.type")

/**
 * Runs this handler for [request] as a new execution caused by the current one, through the server's HTTP
 * interceptors, exactly as a routed request would be but without routing.
 */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(
    request: HttpRequest<PATH>,
): HttpResponse =
    @OptIn(InternalLightningServerApi::class)
    executeWithMetrics(request) { req ->
        this@handleWithMetrics.handle(req)
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
    @OptIn(InternalLightningServerApi::class)
    executeHttpIntercepted(
        request,
        Execution.Http(id = executionId, endpoint = request.path),
        ServerRuntime::routeHttpRequest
    )

public suspend fun ServerRuntime.handle(
    request: HttpRequest<*>,
    executionId: Execution.ID = Execution.ID.generate()
): HttpResponse = executeHttpIntercepted(
    request,
    childHttpExecution(request, executionId),
    ServerRuntime::routeHttpRequest
)

/**
 * Runs [dispatch] for [request] the way this handler would be run: as a new execution caused by the current
 * one, through the server's HTTP interceptors, and within this handler's timeout.
 */
@InternalLightningServerApi
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec> HttpHandler<PATH>.executeWithMetrics(
    request: HttpRequest<PATH>,
    executionId: Execution.ID = Execution.ID.generate(),
    dispatch: suspend ServerRuntime.(HttpRequest<PATH>) -> HttpResponse,
): HttpResponse = engine.executeHttpIntercepted(
    request,
    runtime.childHttpExecution(request, executionId)
) { req ->
    runHandler(this@executeWithMetrics.timeout) { dispatch(req) }
}


// INTERNAL IMPLEMENTATION STUFF

@OptIn(InternalLightningServerApi::class)
private fun ServerRuntime.childHttpExecution(request: HttpRequest<*>, executionId: Execution.ID): Execution.Http =
    Execution.Http(
        id = executionId,
        endpoint = request.path,
        causedBy = execution.id,
        rootExecution = execution.rootExecution
    )

// Per-handler request timeout (HttpHandler.timeout, default 30s), enforced at this single choke point shared
// by every engine instead of being duplicated (and high-risk) in each engine adapter. Cooperative
// cancellation: only interrupts at suspension points.
private suspend fun ServerRuntime.runHandler(
    timeout: Duration,
    action: suspend () -> HttpResponse,
): HttpResponse = instrument("handler") { withTimeout(timeout) { action() } }

@OptIn(InternalLightningServerApi::class)
private suspend fun <PATH : PathSpec> Engine.executeHttpIntercepted(
    request: HttpRequest<PATH>,
    execution: Execution.Http,
    dispatch: suspend ServerRuntime.(HttpRequest<PATH>) -> HttpResponse,
): HttpResponse {
    val method = request.path.method.toString()
    val route = request.path.route()

    return execute("$method $route", execution, TelemetryAttributes {
        put(TelemetryKeys.Http.method, method)
        put(TelemetryKeys.Http.route, route)
        put(TelemetryKeys.Http.target, "/" + request.path.pathSegments.toString())
        put(TelemetryKeys.Http.scheme, request.protocol)
        put(TelemetryKeys.Http.host, request.domain)
        put(TelemetryKeys.Net.peerIp, request.sourceIp)
    }) { trace ->
        var handledError: String? = null
        suspend fun handleError(e: Exception, label: String? = e::class.simpleName): HttpResponse {
            handledError = label
            return try {
                instrument("exceptionHandler") { serverRuntime.server.exceptionHandler.handle(request, e) }
            } catch (_: Exception) {
                handledError = "unhandled_exception"
                HttpResponse(status = HttpStatus.InternalServerError)
            }
        }

        val response = try {
            serverRuntime.server.compiledHttpInterceptors.intercept(request) { req ->
                // Access logging (with the resolved principal) is provided by the opt-in AccessLogInterceptor in
                // the auth module, not hardcoded here — so it can name the principal without core depending on auth.
                // Map handler/route/compression exceptions to responses in-place so the surrounding
                // interceptors (CORS, etc.) still post-process error responses.
                try {
                    dispatch(req)
                } catch (_: TimeoutCancellationException) {
                    // A handler exceeded its HttpHandler.timeout. This is a server-side condition (the server
                    // couldn't finish in time), so it maps to 503 Service Unavailable — NOT 408, which per
                    // RFC 7231 means the client was too slow sending its request. Routed through the normal
                    // exception handler so the error body is formatted consistently. (Other
                    // CancellationExceptions — e.g. client disconnect — are handled by the generic catch below.)
                    serverRuntime.logger.warn { "Request to ${req.path} exceeded its handler timeout." }
                    handleError(
                        HttpStatusException(
                            status = HttpStatus.ServiceUnavailable,
                            detail = "timeout",
                            message = "The request handler exceeded its timeout.",
                        ),
                        label = "timeout",
                    )
                } catch (e: Exception) {
                    serverRuntime.logger.error(e) { "Exception in HTTP" }
                    handleError(e)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception in HTTP interceptor chain" }
            handleError(e)
        }

        trace.enrich(TelemetryAttributes {
            put(TelemetryKeys.Http.statusCode, response.status.code.toLong())
            handledError?.let { put(errorType, it) }
        })
        response
    }
}


private suspend fun <PATH : PathSpec> ServerRuntime.routeHttpRequest(req: HttpRequest<PATH>): HttpResponse = try {
    // Route resolution must live inside this try so that a RouteNotFoundException (e.g. a HEAD
    // request with no HEAD handler, or a missing trailing slash) is caught below and recovered
    // via the HEAD->GET fallback / slash-redirect logic rather than escaping as a bare 404.
    val handler = req.path.resolve().value
    runHandler(handler.timeout) { handler.handle(req) }
} catch (notFound: RouteNotFoundException) {
    when (req.path.method) {
        HttpMethod.HEAD -> {
            // OK, we'll do a get and remove the body. The GET is routed afresh, so it may not be a PATH.
            val getRequest = req.copyWithNewPathType(path = RawHttpEndpoint(req.path.pathSegments, HttpMethod.GET))
            val handler = getRequest.path.resolve().value
            val getResult = runHandler(handler.timeout) { handler.handle(getRequest) }
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
