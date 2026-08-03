package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument
import kotlinx.coroutines.CancellationException

/**
 * Interface for intercepting and modifying HTTP requests and responses.
 *
 * Interceptors provide middleware functionality, allowing you to wrap request handling with
 * cross-cutting concerns like authentication, logging, CORS, rate limiting, etc.
 *
 * Interceptors are chained together and execute in order. Each interceptor receives the request
 * and a continuation function that represents "the rest of the chain". The interceptor can:
 * - Modify the request before passing it forward
 * - Short-circuit and return a response without calling the continuation
 * - Modify the response after calling the continuation
 * - Handle exceptions from downstream handlers
 *
 * Example:
 * ```kotlin
 * val loggingInterceptor = HttpInterceptor { request, cont ->
 *     println("Request: ${request.path}")
 *     val response = cont(request)
 *     println("Response: ${response.status}")
 *     response
 * }
 * ```
 *
 * Install interceptors in your ServerBuilder:
 * ```kotlin
 * object Server : ServerBuilder() {
 *     init {
 *         install(loggingInterceptor)
 *         install(CorsInterceptor(corsSettings))
 *     }
 * }
 * ```
 */
public fun interface HttpInterceptor {
    /**
     * The name of this interceptor, used for instrumentation and debugging.
     * Defaults to the simple class name or "anonymous" for lambdas.
     */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Intercepts an HTTP request, potentially modifying it, and delegates to the continuation.
     *
     * @param request The incoming HTTP request
     * @param cont The continuation representing the rest of the interceptor chain and final handler
     * @return The HTTP response (potentially modified by this interceptor)
     */
    context(runtime: ServerRuntime)
    public suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse

    /**
     * A no-op interceptor that simply passes requests through unchanged.
     * Used as a placeholder when no interceptors are configured.
     */
    public object NoOp : HttpInterceptor {
        context(runtime: ServerRuntime)
        override suspend fun intercept(
            request: HttpRequest<*>,
            cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
        ): HttpResponse {
            return cont(request)
        }
    }
}

/**
 * Wraps the intercept call with instrumentation for performance monitoring, and recovers from
 * exceptions thrown by this interceptor (or anything nested inside it) by converting them to a
 * response via the configured exception handler.
 *
 * This is used internally to track the time spent in each interceptor.
 *
 * ## Why recover here
 * Every interceptor in the chain is composed via nested calls to this function (see
 * [compileAndInstrument]), so recovering at this single point means an exception thrown by *any*
 * interceptor - not just the terminal handler - is turned into a response before it unwinds past
 * the interceptors that wrap it. Those outer interceptors then see a normal return value from
 * their own continuation call and still get to post-process it (e.g. CORS still adds
 * `Access-Control-Allow-Origin` to a response produced by a rate limiter's own thrown exception).
 * Without this, only exceptions from the innermost handler were guaranteed interceptor
 * post-processing; an interceptor throwing directly (as [com.lightningkite.lightningserver.cors.CorsInterceptor]
 * and rate limiters do) would still skip every interceptor wrapping it.
 *
 * [CancellationException] is rethrown unchanged - it signals coroutine cancellation (e.g. a client
 * disconnect), not a request-level failure, and must not be swallowed into a fabricated response.
 *
 * @param request The HTTP request to intercept
 * @param action The continuation function
 * @return The HTTP response
 */
context(server: ServerRuntime)
public suspend inline fun HttpInterceptor.interceptInstrumented(
    request: HttpRequest<*>,
    noinline action: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse,
): HttpResponse {
    return try {
        instrument(name) {
            intercept(request, action)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        try {
            @Suppress("UNCHECKED_CAST")
            server.server.exceptionHandler.handle(request as HttpRequest<PathSpec>, e)
        } catch (_: Exception) {
            HttpResponse(status = HttpStatus.InternalServerError)
        }
    }
}

/**
 * Compiles a list of interceptors into a single chained interceptor with instrumentation.
 *
 * This internal function is used by the framework to combine multiple interceptors into
 * an efficient execution chain. The first interceptor in the list executes first, followed
 * by each subsequent interceptor, and finally the actual handler.
 *
 * @return A single HttpInterceptor that represents the entire chain
 */
internal fun List<HttpInterceptor>.compileAndInstrument(): HttpInterceptor = when (size) {
    0 -> HttpInterceptor.NoOp
    1 -> HttpInterceptor { request, cont -> first().interceptInstrumented(request, cont) }
    else -> reduceIndexed { idx, acc, interceptor ->
        when {
            acc === HttpInterceptor.NoOp -> interceptor
            interceptor === HttpInterceptor.NoOp -> acc

            else -> HttpInterceptor { request, cont ->
                // idx is of the current interceptor in the list, so will start at 1
                if (idx == 1) acc.interceptInstrumented(request) { interceptor.interceptInstrumented(it, cont) }
                else acc.intercept(request) { interceptor.interceptInstrumented(it, cont) }
            }
        }
    }
}

/*
 * TODO: API Recommendations for HttpInterceptor.kt
 *
 * 1. Add a priority or ordering mechanism for interceptors to ensure correct execution order
 *    (e.g., authentication should run before authorization). Currently order depends on
 *    installation order which is implicit.
 *
 * 2. Consider adding lifecycle hooks for interceptors:
 *    - fun onServerStart(runtime: ServerRuntime)
 *    - fun onServerStop(runtime: ServerRuntime)
 *    This would allow interceptors to initialize/cleanup resources.
 *
 * 3. The compileAndInstrument logic is complex and uses idx checking that's fragile.
 *    The comment "will start at 1" suggests the logic is not immediately obvious.
 *    Consider simplifying or adding more detailed comments about why idx==1 is special.
 *
 * 4. Add a way to skip remaining interceptors and jump directly to the handler:
 *    - This would be useful for caching interceptors that want to return cached responses
 *      without executing authentication, etc.
 *
 * 5. Consider adding typed metadata that can be attached to requests by interceptors
 *    for downstream interceptors/handlers to use (e.g., authenticated user, rate limit info).
 *    Currently this must be done via the SerializableCache which requires serialization.
 *
 * 6. The fun interface is convenient but limits having state in interceptors unless you
 *    use a class. Document the pattern for stateful interceptors clearly.
 */