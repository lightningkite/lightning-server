package com.lightningkite.lightningserver

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.TypedData
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.writeString

context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.redirectToGet(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse = HttpResponse(
    status = HttpStatus.SeeOther,
    headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
)

context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.pathMoved(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse = HttpResponse(
    status = HttpStatus.TemporaryRedirect,
    headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
)

context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.pathMovedOld(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse = HttpResponse(
    status = HttpStatus.Found,
    headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
)

context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.pathMovedPermanently(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse = HttpResponse(
    status = HttpStatus.PermanentRedirect,
    headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
)

public fun HttpResponse.Companion.html(
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    builder: HTML.() -> Unit
): HttpResponse = HttpResponse(
    body = TypedData.html(builder),
    status = status,
    headers = headers
)

public fun HttpResponse.Companion.html(
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    content: String
): HttpResponse = HttpResponse(
    body = TypedData.text(content, MediaType.Text.Html),
    status = status,
    headers = headers
)

public fun HttpResponse.Companion.plainText(
    text: String,
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY
): HttpResponse = HttpResponse(
    body = TypedData.text(text, MediaType.Text.Plain),
    status = status,
    headers = headers
)


public fun TypedData.Companion.html(
    body: HTML.() -> Unit,
): TypedData = TypedData.sink(
    mediaType = MediaType.Text.Html,
    emit = { sink ->
        val appendable = object: Appendable {
            override fun append(csq: CharSequence): Appendable {
                sink.writeString(csq)
                return this
            }

            override fun append(c: Char): Appendable {
                sink.writeString(c.toString())
                return this
            }

            override fun append(csq: CharSequence, start: Int, end: Int): Appendable {
                sink.writeString(csq, start, end)
                return this
            }
        }
        appendable.appendHTML().html(block = body)
    }
)

//public inline fun <reified T> TypedData.Companion.json(
//    value: T,
//): TypedData = TypedData.text(
//    text = Serialization.json.encodeToString(value),
//    mediaType = MediaType.Application.Json
//)

public fun TypedData.Companion.path(
    path: Path,
    fileSystem: FileSystem,
    type: MediaType = MediaType.fromExtension(path.name.substringAfterLast('.'))
): TypedData {
    return TypedData.source(
        source = fileSystem.source(path).buffered(),
        mediaType = type,
        size = fileSystem.metadataOrNull(path)!!.size
    )
}

//context(serverRunning: ServerRunning)
//public inline fun <reified T> HttpResponse.Companion.json(
//    value: T,
//    status: HttpStatus = HttpStatus.OK,
//    headers: HttpHeaders.Builder.() -> Unit = {}
//) = HttpResponse(
//    body = TypedData.json(value),
//    status = status,
//    headers = HttpHeaders(headers)
//)