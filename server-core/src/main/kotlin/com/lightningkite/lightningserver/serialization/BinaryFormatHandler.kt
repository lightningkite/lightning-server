package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.websocket.WebSocketFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
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

    override suspend fun <T> invoke(content: WebSocketFrame, serializer: KSerializer<T>): T {
        return when (content) {
            is WebSocketFrame.Binary -> binaryFormat().decodeFromByteArray(serializer, content.content)
            is WebSocketFrame.Text -> binaryFormat().decodeFromBase64(serializer, content.content)
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

    override suspend fun <T> ws(contentType: ContentType, serializer: KSerializer<T>, value: T): WebSocketFrame {
        return if (contentType.parameters["base64"] != null)
            WebSocketFrame.Text(binaryFormat().encodeToBase64(serializer, value))
        else
            WebSocketFrame.Binary(binaryFormat().encodeToByteArray(serializer, value))
    }
}
