package com.lightningkite.lightningserver

import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.Serializable
import kotlin.code
import kotlin.toString

@Serializable
public data class LSError(
    val http: Int,
    val detail: String = "",
    val message: String = "",
    val data: String = "",
) {
}


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
    public val headers: HttpHeaders = HttpHeaders.EMPTY,
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
public class BadRequestException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.BadRequest, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of BadRequestException.
 */
public fun BadRequestException(message: String): BadRequestException = BadRequestException(message = message, detail = "")


/**
 * An HttpStatusException that results in a status code of 401 to be returned to the client
 */
public class UnauthorizedException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.Unauthorized, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of UnauthorizedException.
 */
public fun UnauthorizedException(message: String): UnauthorizedException =
    UnauthorizedException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 403 to be returned to the client
 */
public class ForbiddenException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.Forbidden, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of ForbiddenException.
 */
public fun ForbiddenException(message: String): ForbiddenException = ForbiddenException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 404 to be returned to the client
 */
public class NotFoundException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.NotFound, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of NotFoundException.
 */
public fun NotFoundException(message: String): NotFoundException = NotFoundException(message = message, detail = "")
