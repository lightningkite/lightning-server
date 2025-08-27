package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawPath
import kotlinx.serialization.Serializable

@Serializable
public data class LSError(
    val http: Int,
    val detail: String = "",
    val message: String = "",
    val data: String = "",
    val stackTrace: String? = null,
)

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

public class RouteNotFoundException(
    public val requestedRoute: RawPath<*>
): NotFoundException(
    detail = "not-found",
    message = "No route matching ${requestedRoute} was found.",
    data = requestedRoute.toString(),
)