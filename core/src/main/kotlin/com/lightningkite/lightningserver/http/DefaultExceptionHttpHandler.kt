package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.toTypedData

/**
 * Default exception handler that converts exceptions to appropriate HTTP error responses.
 *
 * This handler implements the following behavior:
 *
 * 1. **HttpStatusException**: Converts to the appropriate HTTP status code and error details
 * 2. **Other exceptions in debug mode**: Returns 500 with exception details and stack trace
 * 3. **Other exceptions in production**: Returns 500 with a generic error message
 *
 * The response body is formatted as an [LSError] and serialized according to the client's
 * Accept header (JSON by default).
 *
 * Stack traces are only included when `generalSettings().debug` is true to avoid leaking
 * implementation details in production.
 */
internal object DefaultExceptionHttpHandler : ExceptionHttpHandler {
    context(server: ServerRuntime)
    override suspend fun handle(
        request: HttpRequest<PathSpec>,
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
        val lsError = if (generalSettings().debug) lsErrorWithoutTrace.copy(stackTrace = exception.stackTraceToString())
        else lsErrorWithoutTrace
        return HttpResponse(
            status = HttpStatus(lsError.http),
            body = lsError.toTypedData(request.headers.accept)
        )
    }
}

/*
 * TODO: API Recommendations for DefaultExceptionHttpHandler.kt
 *
 * 1. The handler doesn't log exceptions - consider adding logging here for all unhandled exceptions
 *    to ensure errors are captured even if monitoring/logging interceptors aren't configured.
 *
 * 2. Stack traces in debug mode could expose sensitive information (file paths, internal logic).
 *    Consider sanitizing or limiting stack trace depth even in debug mode.
 *
 * 3. The generic error message in production ("An unknown error occurred") isn't helpful for debugging.
 *    Consider including a correlation ID that maps to server-side logs.
 *
 * 4. No special handling for common exception types like IllegalArgumentException, NullPointerException.
 *    These could be mapped to 400 Bad Request instead of 500 Internal Server Error when appropriate.
 *
 * 5. The toTypedData call could fail if the Accept header specifies an unsupported format.
 *    Consider wrapping this in a try-catch and falling back to JSON or plain text.
 */

/**
 * Recovers the [LSError] from an error response produced by an [ExceptionHttpHandler].
 *
 * Used where a caller needs the structured error rather than the serialized body — notably a
 * multiplexed endpoint reporting per-sub-request outcomes. Falls back to an error synthesized from
 * the status when the body is absent or is not a serialized [LSError], which happens whenever a
 * handler returns a failure status of its own rather than throwing.
 */
context(server: ServerRuntime)
public suspend fun HttpResponse.toLSError(): LSError {
    body?.let {
        try {
            return it.parse(LSError.serializer())
        } catch (_: Exception) {
            // Not a serialized LSError; fall through to synthesizing one from the status.
        }
    }
    return LSError(http = status.code, detail = "unknown", message = status.toString())
}
