package com.lightningkite.lightningserver.serialization

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.KotlinBytesFormat
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeFromSource
import kotlinx.serialization.json.io.encodeToSink
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.io.encoding.Base64
import kotlin.reflect.KClass

/**
 * MediaTypeCoder implementation for binary serialization formats.
 *
 * Wraps a KotlinX Serialization BinaryFormat and provides MediaTypeCoder functionality.
 * Caches the format instance after first access for performance.
 *
 * @param format A context-aware lambda that returns the BinaryFormat to use
 * @param mediaType The media type this coder handles
 */
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

/**
 * MediaTypeCoder implementation for string-based serialization formats.
 *
 * Wraps a KotlinX Serialization StringFormat and provides MediaTypeCoder functionality.
 * Caches the format instance after first access for performance.
 *
 * @param format A context-aware lambda that returns the StringFormat to use
 * @param mediaType The media type this coder handles
 */
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

/**
 * Specialized MediaTypeCoder for JSON with streaming support.
 *
 * Extends StringFormatMediaTypeCoder with optimizations for JSON:
 * - Higher priority (1.0) makes it the preferred encoder
 * - Streaming support using Source/Sink for efficient large payloads
 *
 * @param json A context-aware lambda that returns the Json format to use
 */
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

    /**
     * Deserializes JSON content with streaming support for Source data.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override context(runtime: ServerRuntime) suspend fun <T> invoke(content: TypedData, serializer: DeserializationStrategy<T>): T {
        return when (val body = content.data) {
            is Data.Source -> body.source.use { formatCached.decodeFromSource(serializer, it) }
            else -> super.invoke(content, serializer)
        }
    }

    /**
     * Serializes to JSON with streaming support via Sink.
     */
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

/**
 * Registers the basic media type coders: JSON, form data, and Kotlin binary format.
 *
 * This extension function should be called during server setup to enable standard
 * serialization formats. The JSON coder is configured with lenient settings suitable
 * for web APIs.
 *
 * **Registered formats:**
 * - application/json (priority 1.0, streaming enabled)
 * - application/x-www-form-urlencoded
 * - application/x-lightningserver-kotlin-bytes
 *
 * @param serializersModule Runtime provider for the SerializersModule to use.
 *                         Defaults to the server's external serialization module.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun ServerBuilder.registerBasicMediaTypeCoders(serializersModule: Runtime<SerializersModule> = Runtime { serverRuntime.externalSerialization.serializersModule }) {
    register(JsonMediaTypeCoder { Json {
        this.serializersModule = serializersModule()
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
        allowStructuredMapKeys = true
        prettyPrint = false
        allowSpecialFloatingPointValues = true
        useAlternativeNames = true
        decodeEnumsCaseInsensitive = true
        allowTrailingComma = true
        allowComments = true
    }})
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

/**
 * Debugging utility to create a human-readable string representation of a SerializersModule.
 *
 * Lists all contextual serializers, polymorphic mappings, and default providers registered
 * in the module. Useful for troubleshooting serialization configuration issues.
 *
 * @return A comma-separated string of module contents
 */
@InternalLightningServerApi
public fun SerializersModule.debugString(): String = buildString {
    dumpTo(object: SerializersModuleCollector {
        override fun <T : Any> contextual(
            kClass: KClass<T>,
            provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>
        ) {
            append("contextual($kClass), ")
        }

        override fun <Base : Any, Sub : Base> polymorphic(
            baseClass: KClass<Base>,
            actualClass: KClass<Sub>,
            actualSerializer: KSerializer<Sub>
        ) {
            append("polymorphic($baseClass, $actualClass), ")
        }

        override fun <Base : Any> polymorphicDefaultSerializer(
            baseClass: KClass<Base>,
            defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?
        ) {
            append("polymorphicDefaultSerializer($baseClass), ")
        }

        override fun <Base : Any> polymorphicDefaultDeserializer(
            baseClass: KClass<Base>,
            defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?
        ) {
            append("polymorphicDefaultDeserializer($baseClass), ")
        }
    })
    if(this.length > 2) {
        this.setLength(this.length - 2)
    }
}
