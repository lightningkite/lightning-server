package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.serialization.Serialization

import com.lightningkite.lightningserver.serialization.toHttpContent
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import java.io.InputStream

open class HttpStatusException(
    val status: HttpStatus,
    val detail: String = "",
    message: String = "",
    val data: String = "",
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    cause: Throwable? = null
): Exception(message, cause) {
    override val message: String get() = super.message!!
    fun toLSError(): LSError = LSError(
        http = status.code,
        detail = detail,
        message = message,
        data = data,
    )
    suspend fun toResponse(request: HttpRequest): HttpResponse {
        if(request.headers.accept.firstOrNull() == ContentType.Text.Html) {
            return HttpResponse(body = HttpContent.Text(string = HtmlDefaults.basePage("""
                <h1>${status.toString().escapeHTML()}</h1>
                <p>${message}</p>
                ${detail.let { "<!--${it.escapeHTML()}-->" } ?: ""}
                ${if(generalSettings().debug) "<!--${stackTraceToString().escapeHTML()}-->" else ""}
            """.trimIndent()), type = ContentType.Text.Html), headers = headers)
        }
        return HttpResponse(
            body = toLSError().toHttpContent(request.headers.accept),
            status = status,
            headers = headers
        )
    }
}

class BadRequestException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null, headers: HttpHeaders = HttpHeaders.EMPTY): HttpStatusException(HttpStatus.BadRequest, detail, message, data, headers, cause)
fun BadRequestException(message: String): BadRequestException = BadRequestException(message = message)
class UnauthorizedException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null, headers: HttpHeaders = HttpHeaders.EMPTY): HttpStatusException(HttpStatus.Unauthorized, detail, message, data, headers, cause)
fun UnauthorizedException(message: String): UnauthorizedException = UnauthorizedException(message = message)
class ForbiddenException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null, headers: HttpHeaders = HttpHeaders.EMPTY): HttpStatusException(HttpStatus.Forbidden, detail, message, data, headers, cause)
fun ForbiddenException(message: String): ForbiddenException = ForbiddenException(message = message)
class NotFoundException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null, headers: HttpHeaders = HttpHeaders.EMPTY): HttpStatusException(HttpStatus.NotFound, detail, message, data, headers, cause)
fun NotFoundException(message: String): NotFoundException = NotFoundException(message = message)
