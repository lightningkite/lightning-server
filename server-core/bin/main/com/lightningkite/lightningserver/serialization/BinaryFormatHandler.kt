package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromHexString
import java.io.InputStream

open class BinaryFormatHandler(
    val binaryFormat: () -> BinaryFormat,
    override val contentType: ContentType,
) : Serialization.HttpContentHandler {
    override suspend fun <T> invoke(content: HttpContent, serializer: KSerializer<T>): T {
        return when (val body = content) {
            is HttpContent.Text -> binaryFormat().decodeFromHexString(serializer, body.string)
            is HttpContent.Binary -> binaryFormat().decodeFromByteArray(
                serializer,
                body.bytes
            )

            is HttpContent.Multipart -> throw BadRequestException("Expected JSON, but got a multipart body.")
            else -> withContext(Dispatchers.IO) {
                body.stream().use {
                    fromStream(it, serializer)
                }
            }
        }
    }

    open suspend fun <T> fromStream(stream: InputStream, serializer: KSerializer<T>): T {
        return binaryFormat().decodeFromByteArray(
            serializer,
            stream.readBytes()
        )
    }

    override suspend fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return HttpContent.Binary(
            binaryFormat().encodeToByteArray(serializer, value),
            contentType
        )
    }
}