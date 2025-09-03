package com.lightningkite.lightningserver.serialization

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy


public interface MediaTypeDecoder {
    public val priority: Float get() = 0f
    public val mediaType: MediaType
    public context(runtime: ServerRuntime) fun accepts(parameters: Map<String, String>): Boolean = true
    public context(runtime: ServerRuntime) suspend operator fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T
    public context(runtime: ServerRuntime) suspend operator fun <T> invoke(content: WebSocketFrame, serializer: DeserializationStrategy<T>): T =
        invoke(when(content) {
            is WebSocketFrame.Binary -> TypedData(Data.Bytes(content.content), mediaType)
            is WebSocketFrame.Text -> TypedData(Data.Text(content.content), mediaType)
        }, serializer)
}

public interface MediaTypeEncoder {
    public val priority: Float get() = 0f
    public val mediaType: MediaType
    public context(runtime: ServerRuntime) fun accepts(parameters: Map<String, String>): Boolean = true
    public context(runtime: ServerRuntime) suspend operator fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData
    public context(runtime: ServerRuntime) suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame =
        invoke(mediaType, serializer, value).let {
            when(it.data) {
                is Data.Text -> WebSocketFrame.Text(it.text())
                else -> {
                    val buffer = Buffer()
                    it.write(buffer)
                    WebSocketFrame.Binary(buffer.readByteArray())
                }
            }
        }
    public context(runtime: ServerRuntime) suspend fun <T> streaming(mediaType: MediaType, serializer: KSerializer<T>, value: T): TypedData =
        invoke(mediaType, serializer, value)
}

public interface MediaTypeCoder : MediaTypeDecoder, MediaTypeEncoder {
    override val priority: Float get() = 0f
    override context(runtime: ServerRuntime) fun accepts(parameters: Map<String, String>): Boolean = true
}