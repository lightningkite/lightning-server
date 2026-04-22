package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint

/**
 * Converts an LSError to an HttpStatusException.
 *
 * @param message Optional override for the error message (defaults to the LSError's message)
 * @param data Optional override for the error data (defaults to the LSError's data)
 * @return An HttpStatusException with the same HTTP status and details
 */
public fun LSError.toException(
    message: String = this.message,
    data: String = this.data
): HttpStatusException = HttpStatusException(
    status = HttpStatus(http),
    detail = detail,
    message = message,
    data = data,
)


/**
 * A Lightning Server exception that is handled differently in requests and tasks.
 * These are caught and result in a well formed response with a proper status code.
 * At anytime if there is a problem with the request such as Unauthorized, you can
 * throw these exceptions to end calculations and send a response.
 */
public open class HttpStatusException(
    public val status: HttpStatus,
    public val detail: String = "",
    message: String = "",
    public val data: String = "",
    cause: Throwable? = null
) : Exception(message, cause) {
    public constructor(lsError: LSError): this(
        status = HttpStatus(lsError.http),
        detail = lsError.detail,
        message = lsError.message,
        data = lsError.data,
    )

    override val message: String get() = super.message!!
    public fun toLSError(): LSError = LSError(
        http = status.code,
        detail = detail,
        message = message,
        data = data,
    )
}

/**
 * An HttpStatusException that results in a status code of 400 to be returned to the client
 */
public open class BadRequestException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
) : HttpStatusException(HttpStatus.BadRequest, detail, message, data, cause)

/**
 * A Helper function for creating an instance of BadRequestException.
 */
public fun BadRequestException(message: String): BadRequestException = BadRequestException(message = message, detail = "")


/**
 * An HttpStatusException that results in a status code of 401 to be returned to the client
 */
public open class UnauthorizedException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
) : HttpStatusException(HttpStatus.Unauthorized, detail, message, data, cause)

/**
 * A Helper function for creating an instance of UnauthorizedException.
 */
public fun UnauthorizedException(message: String): UnauthorizedException =
    UnauthorizedException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 403 to be returned to the client
 */
public open class ForbiddenException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
) : HttpStatusException(HttpStatus.Forbidden, detail, message, data, cause)

/**
 * A Helper function for creating an instance of ForbiddenException.
 */
public fun ForbiddenException(message: String): ForbiddenException = ForbiddenException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 404 to be returned to the client
 */
public open class NotFoundException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
) : HttpStatusException(HttpStatus.NotFound, detail, message, data, cause)

/**
 * A Helper function for creating an instance of NotFoundException.
 */
public fun NotFoundException(message: String): NotFoundException = NotFoundException(message = message, detail = "")

/**
 * A specialized NotFoundException thrown when a requested route cannot be matched.
 *
 * @property requestedRoute The endpoint that was requested but not found
 */
public class RouteNotFoundException(
    public val requestedRoute: RawHttpEndpoint<*>
): NotFoundException(
    detail = "not-found",
    message = "No route matching $requestedRoute was found.",
    data = requestedRoute.toString(),
)

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding more common HTTP status exceptions (e.g., ConflictException for 409,
 *    UnprocessableEntityException for 422, TooManyRequestsException for 429)
 * 2. Consider adding builder-style methods for adding headers to exceptions
 * 3. The detail field could benefit from documentation explaining its intended use vs message
 * 4. Consider making LSError a documented public type if it isn't already
 */