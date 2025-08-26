package com.lightningkite.lightningserver.serialization

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.register
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KotlinBytesFormat
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeFromSource
import kotlinx.serialization.json.io.encodeToSink
import kotlinx.serialization.modules.SerializersModule
import kotlin.io.encoding.Base64

public open class BinaryFormatMediaTypeCoder(
    private val stringFormat: () -> BinaryFormat,
    override val mediaType: MediaType,
) : MediaTypeCoder {

    override suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return stringFormat().decodeFromByteArray(serializer, content.data.bytes())
    }

    override suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData =
        TypedData.bytes(
            stringFormat().encodeToByteArray(serializer, value),
            mediaType
        )

    override suspend fun <T> invoke(content: WebSocketFrame, serializer: DeserializationStrategy<T>): T {
        return when (content) {
            is WebSocketFrame.Binary -> stringFormat().decodeFromByteArray(serializer, content.content)
            is WebSocketFrame.Text -> stringFormat().decodeFromByteArray(serializer, Base64.decode(content.content))
        }
    }

    override suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame {
        return WebSocketFrame.Binary(stringFormat().encodeToByteArray(serializer, value))
    }
}

public open class StringFormatMediaTypeCoder(
    private val stringFormat: () -> StringFormat,
    override val mediaType: MediaType,
) : MediaTypeCoder {

    override suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return stringFormat().decodeFromString(serializer, content.data.text())
    }

    override suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData =
        TypedData.text(
            stringFormat().encodeToString(serializer, value),
            mediaType
        )

    override suspend fun <T> invoke(content: WebSocketFrame, serializer: DeserializationStrategy<T>): T {
        return when (content) {
            is WebSocketFrame.Binary -> stringFormat().decodeFromString(
                serializer,
                content.content.toString(Charsets.UTF_8)
            )

            is WebSocketFrame.Text -> stringFormat().decodeFromString(serializer, content.content)
        }
    }

    override suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame {
        return WebSocketFrame.Text(stringFormat().encodeToString(serializer, value))
    }
}

public class JsonMediaTypeCoder(
    private val json: () -> Json,
) : StringFormatMediaTypeCoder(json, MediaType.Application.Json) {
    override val priority: Float get() = 1f

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return when (val body = content.data) {
            is Data.Source -> body.source.use { json().decodeFromSource(serializer, it) }
            else -> super.invoke(content, serializer)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData {
        return TypedData.sink(
            mediaType,
            emit = {
                it.use { json().encodeToSink(serializer, value, it) }
            }
        )
    }
}

public fun ServerBuilder.basicMediaTypeCoders(serializersModule: SerializersModule = externalSerialization) {
    val json = Json {
        this.serializersModule = serializersModule
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
        allowStructuredMapKeys = true
        prettyPrint = false
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        useAlternativeNames = true
        decodeEnumsCaseInsensitive = true
        allowTrailingComma = true
        allowComments = true
    }
    register(JsonMediaTypeCoder { json })
    register(
        StringFormatMediaTypeCoder(
            stringFormat = { FormDataFormat(serializersModule) },
            mediaType = MediaType.Application.FormUrlEncoded
        )
    )
    register(
        BinaryFormatMediaTypeCoder(
            stringFormat = { KotlinBytesFormat(serializersModule) },
            mediaType = MediaType("application", "x-lightningserver-kotlin-bytes")
        )
    )
}
