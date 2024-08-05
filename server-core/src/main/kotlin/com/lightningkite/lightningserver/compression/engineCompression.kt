package com.lightningkite.lightningserver.compression

import com.lightningkite.lightningserver.http.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

suspend fun HttpResponse.extensionForEngineCompression(request: HttpRequest): HttpResponse {
    for(option in request.headers.getMany(HttpHeader.AcceptEncoding).flatMap { it.split(',') }.map { it.trim() }) {
        when(option) {
            "gzip" -> return copy(headers = HttpHeaders {
                set(headers)
                set(HttpHeader.ContentEncoding, "gzip")
            }, body = body?.bytes()?.gzip()?.let { HttpContent.Binary(it, body.type) })
        }
    }
    return this
}

fun ByteArray.gzip(): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use {
        it.write(this)
        it.flush()
    }
    return out.toByteArray()
}
fun ByteArray.ungzip(): ByteArray {
    return GZIPInputStream(ByteArrayInputStream(this)).readBytes()
}
