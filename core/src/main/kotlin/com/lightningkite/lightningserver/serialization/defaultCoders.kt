package com.lightningkite.lightningserver.serialization

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.register
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KotlinBytesFormat
import com.lightningkite.services.data.TypedData
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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
    private val json: () -> Json
) : StringFormatMediaTypeCoder(json, MediaType.Application.Json) {
    override val priority: Float get() = 1f
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return when (val body = content.data) {
            is Data.Source -> json().decodeFromStream(serializer, body.source.asInputStream())
            else -> super.invoke(content, serializer)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData {
        return TypedData.sink(
            mediaType,
            emit = {
                json().encodeToStream(serializer, value, it.asOutputStream())
            }
        )
    }
}

public fun ServerBuilder.basicMediaTypeCoders(serializersModule: SerializersModule = externalSerialization) {
    register(JsonMediaTypeCoder {
        Json {
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
    })
    register(StringFormatMediaTypeCoder(
        stringFormat = { FormDataFormat(serializersModule) },
        mediaType = MediaType.Application.FormUrlEncoded
    ))
    register(BinaryFormatMediaTypeCoder(
        stringFormat = { KotlinBytesFormat(serializersModule) },
        mediaType = MediaType("application", "x-lightningserver-kotlin-bytes")
    ))
}
