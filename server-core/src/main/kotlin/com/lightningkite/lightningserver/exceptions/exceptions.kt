package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.serialization.Serialization

import com.lightningkite.lightningserver.serialization.toHttpContent
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
        return HttpResponse(
            body = toLSError().toHttpContent(request.headers.accept),
            status = status
        )
    }
}

class BadRequestException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null): HttpStatusException(HttpStatus.BadRequest, detail, message, data, cause)
fun BadRequestException(message: String): BadRequestException = BadRequestException(message = message)
class UnauthorizedException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null): HttpStatusException(HttpStatus.Unauthorized, detail, message, data, cause)
fun UnauthorizedException(message: String): UnauthorizedException = UnauthorizedException(message = message)
class ForbiddenException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null): HttpStatusException(HttpStatus.Forbidden, detail, message, data, cause)
fun ForbiddenException(message: String): ForbiddenException = ForbiddenException(message = message)
class NotFoundException(detail: String = "", message: String = "", data: String = "", cause: Throwable? = null): HttpStatusException(HttpStatus.NotFound, detail, message, data, cause)
fun NotFoundException(message: String): NotFoundException = NotFoundException(message = message)
