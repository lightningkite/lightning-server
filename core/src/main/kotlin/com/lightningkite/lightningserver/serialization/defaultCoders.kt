package com.lightningkite.lightningserver.serialization

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
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
    private val format: context(ServerRuntime) () -> BinaryFormat,
    override val mediaType: MediaType,
) : MediaTypeCoder {

    private var _formatCached: BinaryFormat? = null
    private context(runtime: ServerRuntime) val formatCached: BinaryFormat get(){
        return _formatCached ?: run {
            val retrieved = format()
            _formatCached = retrieved
            retrieved
        }
    }

    override context(runtime: ServerRuntime) suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return formatCached.decodeFromByteArray(serializer, content.data.bytes())
    }

    override context(runtime: ServerRuntime) suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData =
        TypedData.bytes(
            formatCached.encodeToByteArray(serializer, value),
            mediaType
        )

    override context(runtime: ServerRuntime) suspend fun <T> invoke(content: WebSocketFrame, serializer: DeserializationStrategy<T>): T {
        return when (content) {
            is WebSocketFrame.Binary -> formatCached.decodeFromByteArray(serializer, content.content)
            is WebSocketFrame.Text -> formatCached.decodeFromByteArray(serializer, Base64.decode(content.content))
        }
    }

    override context(runtime: ServerRuntime) suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame {
        return WebSocketFrame.Binary(formatCached.encodeToByteArray(serializer, value))
    }
}

public open class StringFormatMediaTypeCoder(
    private val format: context(ServerRuntime) () -> StringFormat,
    override val mediaType: MediaType,
) : MediaTypeCoder {

    private var _formatCached: StringFormat? = null
    private context(runtime: ServerRuntime) val formatCached: StringFormat get(){
        return _formatCached ?: run {
            val retrieved = format()
            _formatCached = retrieved
            retrieved
        }
    }

    override context(runtime: ServerRuntime) suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return formatCached.decodeFromString(serializer, content.data.text())
    }

    override context(runtime: ServerRuntime) suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData =
        TypedData.text(
            formatCached.encodeToString(serializer, value),
            mediaType
        )

    override context(runtime: ServerRuntime) suspend fun <T> invoke(content: WebSocketFrame, serializer: DeserializationStrategy<T>): T {
        return when (content) {
            is WebSocketFrame.Binary -> formatCached.decodeFromString(
                serializer,
                content.content.toString(Charsets.UTF_8)
            )

            is WebSocketFrame.Text -> formatCached.decodeFromString(serializer, content.content)
        }
    }

    override context(runtime: ServerRuntime) suspend fun <T> ws(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): WebSocketFrame {
        return WebSocketFrame.Text(formatCached.encodeToString(serializer, value))
    }
}

public class JsonMediaTypeCoder(
    private val json: context(ServerRuntime) () -> Json,
) : StringFormatMediaTypeCoder(json, MediaType.Application.Json) {
    override val priority: Float get() = 1f

    private var _formatCached: Json? = null
    private context(runtime: ServerRuntime) val formatCached: Json get(){
        return _formatCached ?: run {
            val retrieved = json()
            _formatCached = retrieved
            retrieved
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override context(runtime: ServerRuntime) suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return when (val body = content.data) {
            is Data.Source -> body.source.use { formatCached.decodeFromSource(serializer, it) }
            else -> super.invoke(content, serializer)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override context(runtime: ServerRuntime) suspend fun <T> invoke(mediaType: MediaType, serializer: SerializationStrategy<T>, value: T): TypedData {
        return TypedData.sink(
            mediaType,
            emit = {
                it.use { formatCached.encodeToSink(serializer, value, it) }
            }
        )
    }
}

public fun ServerBuilder.registerBasicMediaTypeCoders(serializersModule: Runtime<SerializersModule> = externalSerialization) {
    register(JsonMediaTypeCoder { Json {
        this.serializersModule = serializersModule()
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
    } })
    register(
        StringFormatMediaTypeCoder(
            format = { FormDataFormat(serializersModule()) },
            mediaType = MediaType.Application.FormUrlEncoded
        )
    )
    register(
        BinaryFormatMediaTypeCoder(
            format = { KotlinBytesFormat(serializersModule()) },
            mediaType = MediaType("application", "x-lightningserver-kotlin-bytes")
        )
    )
}
