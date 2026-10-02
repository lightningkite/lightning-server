package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.toTypedData
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Interface for handling exceptions that occur during HTTP request processing.
 *
 * When an exception is thrown by a handler or interceptor, the exception handler converts
 * it into an appropriate HTTP response. The default implementation ([HttpExceptionHandler.Default])
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
 *             else -> ExceptionHttpHandler.Default.handle(request, exception)
 *         }
 *     }
 * }
 * ```
 */
public interface HttpExceptionHandler {
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
    public suspend fun handle(request: HttpRequest<*>, exception: Exception): HttpResponse

    public object Default : HttpExceptionHandler {
        context(server: ServerRuntime)
        override suspend fun handle(
            request: HttpRequest<*>,
            exception: Exception,
        ): HttpResponse {
            val lsErrorWithoutTrace = when {
                exception is HttpStatusException -> exception.toLSError()

                generalSettings().debug -> LSError(
                    HttpStatus.InternalServerError.code,
                    detail = exception::class.simpleName ?: "Unknown",
                    message = exception.message ?: "No exception message provided."
                )

                else -> LSError(
                    HttpStatus.InternalServerError.code,
                    detail = "unknown",
                    message = "An unknown error occurred"
                )
            }

            val lsError =
                if (generalSettings().debug) lsErrorWithoutTrace.copy(stackTrace = exception.stackTraceToString())
                else lsErrorWithoutTrace

            return HttpResponse(
                status = HttpStatus(lsError.http),
                body = lsError.toTypedData(request.headers.accept)
            )
        }
    }
}

