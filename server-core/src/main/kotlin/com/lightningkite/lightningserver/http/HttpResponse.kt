package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.html.HTML

data class HttpResponse(
    val body: HttpContent? = null,
    val status: HttpStatus = if (body != null) HttpStatus.OK else HttpStatus.NoContent,
    val headers: HttpHeaders = HttpHeaders.EMPTY,
) {
    constructor(
        body: HttpContent? = null,
        status: HttpStatus = if (body != null) HttpStatus.OK else HttpStatus.NoContent,
        headers: HttpHeaders.Builder.() -> Unit
    ) : this(body, status, HttpHeaders.Builder().apply(headers).build())

    constructor(
        body: HttpContent? = null,
        status: HttpStatus = if (body != null) HttpStatus.OK else HttpStatus.NoContent,
        headers: Map<String, String>
    ) : this(body, status, HttpHeaders(headers))

    companion object {
        fun redirectToGet(to: String, headers: HttpHeaders.Builder.() -> Unit = {}) = HttpResponse(
            status = HttpStatus.SeeOther,
            headers = { set(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)); headers() },
        )

        fun pathMoved(to: String, headers: HttpHeaders.Builder.() -> Unit = {}) = HttpResponse(
            status = HttpStatus.TemporaryRedirect,
            headers = { set(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)); headers() },
        )

        fun pathMovedOld(to: String, headers: HttpHeaders.Builder.() -> Unit = {}) = HttpResponse(
            status = HttpStatus.Found,
            headers = { set(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)); headers() },
        )

        fun pathMovedPermanently(to: String, headers: HttpHeaders.Builder.() -> Unit = {}) = HttpResponse(
            status = HttpStatus.PermanentRedirect,
            headers = { set(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)); headers() },
        )

        fun html(
            status: HttpStatus = HttpStatus.OK,
            headers: HttpHeaders.Builder.() -> Unit = {},
            builder: HTML.() -> Unit
        ) = HttpResponse(
            body = HttpContent.html(builder),
            status = status,
            headers = headers
        )

        fun html(
            status: HttpStatus = HttpStatus.OK,
            headers: HttpHeaders.Builder.() -> Unit = {},
            content: String
        ) = HttpResponse(
            body = HttpContent.Text(content, ContentType.Text.Html),
            status = status,
            headers = headers
        )

        fun plainText(
            text: String,
            status: HttpStatus = HttpStatus.OK,
            headers: HttpHeaders.Builder.() -> Unit = {}
        ) = HttpResponse(
            body = HttpContent.Text(text, ContentType.Text.Plain),
            status = status,
            headers = headers
        )

        inline fun <reified T> json(
            value: T,
            status: HttpStatus = HttpStatus.OK,
            headers: HttpHeaders.Builder.() -> Unit = {}
        ) = HttpResponse(
            body = HttpContent.json(value),
            status = status,
            headers = HttpHeaders(headers)
        )
    }
}
