package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.serialization.Serialization

import com.lightningkite.lightningserver.serialization.toHttpContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.io.InputStream

open class HttpStatusException(
    val status: HttpStatus,
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    val body: Body<*>? = null,
    cause: Throwable? = null
): Exception("$status: ${body?.data}", cause) {
    class Body<T>(val data: T, val serializer: KSerializer<T>) {
        suspend fun toHttpContent(acceptedTypes: List<ContentType>): HttpContent? = data.toHttpContent(acceptedTypes, serializer)
    }
    companion object {
        inline fun <reified T> toBody(value: T): Body<T> = Body(value, Serialization.module.serializer())
    }
    suspend fun toResponse(request: HttpRequest): HttpResponse = HttpResponse(
        status = status,
        body = body?.toHttpContent(request.headers.accept),
        headers = headers
    )
}


inline fun <reified T> BadRequestException(body: T, headers: HttpHeaders.Builder.()->Unit = {}, cause: Throwable? = null) = BadRequestException(HttpStatusException.toBody(body), HttpHeaders.Builder().apply(headers).build(), cause = cause)
class BadRequestException(body: Body<*>? = null, headers: HttpHeaders = HttpHeaders.EMPTY, cause: Throwable? = null): HttpStatusException(HttpStatus.BadRequest, headers, body, cause) {
    constructor(body: Body<*>? = null, headers: HttpHeaders.Builder.()->Unit, cause: Throwable? = null):this(body, HttpHeaders.Builder().apply(headers).build(), cause = cause)
}
inline fun <reified T> UnauthorizedException(body: T, headers: HttpHeaders.Builder.()->Unit = {}, cause: Throwable? = null) = UnauthorizedException(HttpStatusException.toBody(body), HttpHeaders.Builder().apply(headers).build(), cause = cause)
class UnauthorizedException(body: Body<*>? = null, headers: HttpHeaders = HttpHeaders.EMPTY, cause: Throwable? = null): HttpStatusException(HttpStatus.Unauthorized, headers, body, cause) {
    constructor(body: Body<*>? = null, headers: HttpHeaders.Builder.()->Unit, cause: Throwable? = null):this(body, HttpHeaders.Builder().apply(headers).build(), cause = cause)
}
inline fun <reified T> ForbiddenException(body: T, headers: HttpHeaders.Builder.()->Unit = {}, cause: Throwable? = null) = ForbiddenException(HttpStatusException.toBody(body), HttpHeaders.Builder().apply(headers).build(), cause = cause)
class ForbiddenException(body: Body<*>? = null, headers: HttpHeaders = HttpHeaders.EMPTY, cause: Throwable? = null): HttpStatusException(HttpStatus.Forbidden, headers, body, cause) {
    constructor(body: Body<*>? = null, headers: HttpHeaders.Builder.()->Unit, cause: Throwable? = null):this(body, HttpHeaders.Builder().apply(headers).build(), cause = cause)
}
inline fun <reified T> NotFoundException(body: T, headers: HttpHeaders.Builder.()->Unit = {}, cause: Throwable? = null) = NotFoundException(HttpStatusException.toBody(body), HttpHeaders.Builder().apply(headers).build(), cause = cause)
class NotFoundException(body: Body<*>? = null, headers: HttpHeaders = HttpHeaders.EMPTY, cause: Throwable? = null): HttpStatusException(HttpStatus.NotFound, headers, body, cause) {
    constructor(body: Body<*>? = null, headers: HttpHeaders.Builder.()->Unit, cause: Throwable? = null):this(body, HttpHeaders.Builder().apply(headers).build(), cause = cause)
}