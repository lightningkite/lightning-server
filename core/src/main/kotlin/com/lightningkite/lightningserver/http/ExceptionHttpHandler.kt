package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Interface for handling exceptions that occur during HTTP request processing.
 *
 * When an exception is thrown by a handler or interceptor, the exception handler converts
 * it into an appropriate HTTP response. The default implementation ([DefaultExceptionHttpHandler])
 * converts [HttpStatusException] instances to their corresponding status codes and error details,
 * and returns 500 Internal Server Error for other exceptions.
 *
 * In debug mode, stack traces are included in responses to aid development.
 *
 * Example custom exception handler:
 * ```kotlin
 * object CustomExceptionHandler : ExceptionHttpHandler {
 *     context(server: ServerRuntime)
 *     override suspend fun handle(request: HttpRequest<PathSpec>, exception: Exception): HttpResponse {
 *         return when (exception) {
 *             is ValidationException -> HttpResponse(
 *                 status = HttpStatus.BadRequest,
 *                 body = TypedData.json(mapOf("errors" to exception.errors))
 *             )
 *             else -> DefaultExceptionHttpHandler.handle(request, exception)
 *         }
 *     }
 * }
 * ```
 */
public interface ExceptionHttpHandler {
    /**
     * The maximum duration for handling an exception.
     * Defaults to 30 seconds to match handler timeouts.
     */
    public val timeout: Duration get() = 30.seconds

    /**
     * Converts an exception into an HTTP response.
     *
     * @param request The request that was being processed when the exception occurred
     * @param exception The exception that was thrown
     * @return An HTTP response representing the error
     */
    context(server: ServerRuntime)
    public suspend fun handle(request: HttpRequest<PathSpec>, exception: Exception): HttpResponse

}

/*
 * TODO: API Recommendations for ExceptionHttpHandler.kt
 *
 * 1. Add lifecycle hooks for exception logging/monitoring before response generation:
 *    - fun onException(request: HttpRequest<PathSpec>, exception: Exception)
 *    This would allow centralized error tracking without duplicating response logic.
 *
 * 2. Consider supporting exception handler chains similar to interceptors:
 *    - Allow multiple exception handlers to try handling an exception
 *    - Fall back to next handler if one returns null
 *
 * 3. Add a way to provide context-specific error details based on the exception type:
 *    - Interface could include error codes, user-friendly messages, etc.
 *
 * 4. The timeout applies to exception handling but what happens if handling times out?
 *    Document the fallback behavior or add a simple emergency handler.
 */

