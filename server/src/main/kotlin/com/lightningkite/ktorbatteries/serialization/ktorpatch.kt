package com.lightningkite.ktorbatteries.serialization

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.plugins.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.modules.SerializersModule

/**
 * Creates a converter serializing with the specified string [format] and
 * [defaultCharset] (optional, usually it is UTF-8).
 */
@OptIn(ExperimentalSerializationApi::class)
public class KotlinxSerializationConverterPatch(
    private val format: SerialFormat,
) : ContentConverter {
    init {
        require(format is BinaryFormat || format is StringFormat) {
            "Only binary and string formats are supported, " +
                    "$format is not supported."
        }
    }

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any
    ): OutgoingContent {
        try {
            return serializationBase.serialize(
                SerializationNegotiationParameters(
                    format,
                    value,
                    typeInfo,
                    charset,
                    contentType
                )
            )
        } catch (e: SerializationException) {
            throw ContentConvertException("Could not serialize $value.", e)
        }
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        val serializer = serializerFromTypeInfo(typeInfo, format.serializersModule)
        val contentPacket = content.readRemaining()

        try {
            return when (format) {
                is StringFormat -> format.decodeFromString(serializer, contentPacket.readText(charset))
                is BinaryFormat -> format.decodeFromByteArray(serializer, contentPacket.readBytes())
                else -> {
                    contentPacket.discard()
                    error("Unsupported format $format")
                }
            }
        } catch (e: SerializationException) {
            throw ContentConvertException(e.message ?: "Could not parse content.", e)
        }
    }

    private val serializationBase = object : KotlinxSerializationBase<OutgoingContent.ByteArrayContent>(format) {
        override suspend fun serializeContent(parameters: SerializationParameters): OutgoingContent.ByteArrayContent {
            if (parameters !is SerializationNegotiationParameters) {
                error(
                    "parameters type is ${parameters::class.simpleName}," +
                            " but expected ${SerializationNegotiationParameters::class.simpleName}"
                )
            }
            return serializeContent(
                parameters.serializer,
                parameters.format,
                parameters.value,
                parameters.contentType,
                parameters.charset
            )
        }
    }

    private fun serializeContent(
        serializer: KSerializer<*>,
        format: SerialFormat,
        value: Any,
        contentType: ContentType,
        charset: Charset
    ): OutgoingContent.ByteArrayContent {
        @Suppress("UNCHECKED_CAST")
        return when (format) {
            is StringFormat -> {
                val content = format.encodeToString(serializer as KSerializer<Any>, value)
                TextContent(content, contentType.withCharsetIfNeeded(charset))
            }
            is BinaryFormat -> {
                val content = format.encodeToByteArray(serializer as KSerializer<Any>, value)
                ByteArrayContent(content, contentType)
            }
            else -> error("Unsupported format $format")
        }
    }
}

/**
 * Register kotlinx.serialization converter into [ContentNegotiation] plugin
 * with the specified [contentType] and binary [format] (such as CBOR, ProtoBuf)
 */
public fun Configuration.serializationPatch(
    contentType: ContentType,
    format: BinaryFormat
) {
    register(
        contentType,
        KotlinxSerializationConverterPatch(format)
    )
}

/**
 * Register kotlinx.serialization converter into [ContentNegotiation] plugin
 * with the specified [contentType] and string [format] (such as Json)
 */
public fun Configuration.serializationPatch(
    contentType: ContentType,
    format: StringFormat
) {
    register(
        contentType,
        KotlinxSerializationConverterPatch(format)
    )
}

private abstract class KotlinxSerializationBase<T>(
    private val format: SerialFormat
) {
    internal abstract suspend fun serializeContent(parameters: SerializationParameters): T

    internal suspend fun serialize(
        parameters: SerializationParameters
    ): T {
        val result = try {
            serializerFromTypeInfo(parameters.typeInfo, format.serializersModule).let {
                parameters.serializer = it
                serializeContent(parameters)
            }
        } catch (cause: SerializationException) {
            // can fail due to
            // 1. https://github.com/Kotlin/kotlinx.serialization/issues/1163)
            // 2. mismatching between compile-time and runtime types of the response.
            null
        }
        if (result != null) {
            return result
        }
        val guessedSearchSerializer = guessSerializer(parameters.value, format.serializersModule)
        parameters.serializer = guessedSearchSerializer
        return serializeContent(parameters)
    }
}

private open class SerializationParameters(
    open val format: SerialFormat,
    open val value: Any,
    open val typeInfo: TypeInfo,
    open val charset: Charset
) {
    lateinit var serializer: KSerializer<*>
}

private class SerializationNegotiationParameters(
    override val format: SerialFormat,
    override val value: Any,
    override val typeInfo: TypeInfo,
    override val charset: Charset,
    val contentType: ContentType
) : SerializationParameters(format, value, typeInfo, charset)

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
private fun serializerFromTypeInfo(
    typeInfo: TypeInfo,
    module: SerializersModule
): KSerializer<*> {
    return typeInfo.kotlinType
        ?.let { type ->
            if (type.arguments.isEmpty()) null // fallback to simple case because of
            // https://github.com/Kotlin/kotlinx.serialization/issues/1870
            else module.serializerOrNull(type)
        }
        ?: module.getContextual(typeInfo.type)
        ?: typeInfo.type.serializer()
}

@Suppress("UNCHECKED_CAST")
private fun guessSerializer(value: Any, module: SerializersModule): KSerializer<Any> = when (value) {
    is List<*> -> ListSerializer(value.elementSerializer(module))
    is Array<*> -> value.firstOrNull()?.let { guessSerializer(it, module) } ?: ListSerializer(String.serializer())
    is Set<*> -> SetSerializer(value.elementSerializer(module))
    is Map<*, *> -> {
        val keySerializer = value.keys.elementSerializer(module)
        val valueSerializer = value.values.elementSerializer(module)
        MapSerializer(keySerializer, valueSerializer)
    }
    else -> {
        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        module.getContextual(value::class) ?: value::class.serializer()
    }
} as KSerializer<Any>

@OptIn(ExperimentalSerializationApi::class)
private fun Collection<*>.elementSerializer(module: SerializersModule): KSerializer<*> {
    val serializers: List<KSerializer<*>> =
        filterNotNull().map { guessSerializer(it, module) }.distinctBy { it.descriptor.serialName }

    if (serializers.size > 1) {
        error(
            "Serializing collections of different element types is not yet supported. " +
                    "Selected serializers: ${serializers.map { it.descriptor.serialName }}"
        )
    }

    val selected = serializers.singleOrNull() ?: String.serializer()

    if (selected.descriptor.isNullable) {
        return selected
    }

    @Suppress("UNCHECKED_CAST")
    selected as KSerializer<Any>

    if (any { it == null }) {
        return selected.nullable
    }

    return selected
}
