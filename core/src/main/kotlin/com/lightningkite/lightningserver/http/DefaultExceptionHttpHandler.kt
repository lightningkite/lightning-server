package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
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
        exception: Exception
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

