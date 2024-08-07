package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.toHttpContent
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.util.*
import kotlinx.datetime.Instant


/**
 * A Lightning Server exception that is handled differently in requests and tasks.
 * These are caught and result in a well formed response with a proper status code.
 * At anytime if there is a problem with the request such as Unauthorized, you can
 * throw these exceptions to end calculations and send a response.
 */
open class HttpStatusException(
    val status: HttpStatus,
    val detail: String = "",
    message: String = "",
    val data: String = "",
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    cause: Throwable? = null
) : Exception(message, cause) {
    constructor(lsError: LSError): this(
        status = HttpStatus(lsError.http),
        detail = lsError.detail,
        message = lsError.message,
        data = lsError.data,
    )

    override val message: String get() = super.message!!
    fun toLSError(): LSError = LSError(
        http = status.code,
        detail = detail,
        message = message,
        data = data,
    )

    suspend fun toResponse(request: HttpRequest): HttpResponse {
        if (request.headers.accept.firstOrNull() == ContentType.Text.Html) {
            return HttpResponse(body = HttpContent.Text(string = HtmlDefaults.basePage("""
                <h1>${status.toString().escapeHTML()}</h1>
                <p>${message}</p>
                ${detail.let { "<!--${it.escapeHTML()}-->" }}
                ${if (generalSettings().debug) "<!--${stackTraceToString().escapeHTML()}-->" else ""}
            """.trimIndent()), type = ContentType.Text.Html), headers = headers)
        }
        return HttpResponse(
            body = toLSError().toHttpContent(request.headers.accept),
            status = status,
            headers = headers
        )
    }
}

/**
 * An HttpStatusException that results in a status code of 400 to be returned to the client
 */
class BadRequestException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.BadRequest, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of BadRequestException.
 */
fun BadRequestException(message: String): BadRequestException = BadRequestException(message = message, detail = "")


/**
 * An HttpStatusException that results in a status code of 401 to be returned to the client
 */
class UnauthorizedException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.Unauthorized, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of UnauthorizedException.
 */
fun UnauthorizedException(message: String): UnauthorizedException =
    UnauthorizedException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 403 to be returned to the client
 */
class ForbiddenException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.Forbidden, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of ForbiddenException.
 */
fun ForbiddenException(message: String): ForbiddenException = ForbiddenException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 404 to be returned to the client
 */
class NotFoundException(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.NotFound, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of NotFoundException.
 */
fun NotFoundException(message: String): NotFoundException = NotFoundException(message = message, detail = "")

/**
 * An HttpStatusException that results in a status code of 500 to be returned to the client
 */
class InternalServerError(
    detail: String = "",
    message: String = "",
    data: String = "",
    cause: Throwable? = null,
    headers: HttpHeaders = HttpHeaders.EMPTY
) : HttpStatusException(HttpStatus.InternalServerError, detail, message, data, headers, cause)

/**
 * A Helper function for creating an instance of InternalServerError.
 */
suspend fun InternalServerError(details: String = "", cause: Throwable): InternalServerError {
    exceptionSettings().report(cause)
    return InternalServerError(message = "Whoops, Something went wrong.", detail = details)
}
