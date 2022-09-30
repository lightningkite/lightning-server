package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import java.io.InputStream

open class StringFormatHandler(
    val stringFormat: () -> StringFormat,
    override val contentType: ContentType,
) : Serialization.HttpContentHandler {
    override suspend fun <T> invoke(content: HttpContent, serializer: KSerializer<T>): T {
        return when (val body = content) {
            is HttpContent.Text -> stringFormat().decodeFromString(serializer, body.string)
            is HttpContent.Binary -> stringFormat().decodeFromString(
                serializer,
                body.bytes.toString(Charsets.UTF_8)
            )

            is HttpContent.Multipart -> throw BadRequestException("Expected JSON, but got a multipart body.")
            else -> withContext(Dispatchers.IO) {
                body.stream().use {
                    fromStream(contentType, it, serializer)
                }
            }
        }
    }

    open suspend fun <T> fromStream(contentType: ContentType, stream: InputStream, serializer: KSerializer<T>): T {
        return stringFormat().decodeFromString(
            serializer,
            stream.reader().readText()
        )
    }

    override suspend fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return HttpContent.Text(
            stringFormat().encodeToString(serializer, value),
            contentType
        )
    }
}