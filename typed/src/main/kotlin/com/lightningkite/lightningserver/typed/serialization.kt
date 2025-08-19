package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Extensions
import com.lightningkite.lightningserver.definition.MutableExtensions
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlin.collections.List

public class MediaTypeEncoderRegistry(
    private val registry: HashMap<MediaType, ArrayList<MediaTypeEncoder>> = HashMap()
) : Map<MediaType, List<MediaTypeEncoder>> by registry {
    public fun register(encoder: MediaTypeEncoder) {
        registry.getOrPut(encoder.mediaType, ::ArrayList).apply {
            add(encoder)
            sortBy { it.priority }
        }
    }

    // extension key for registration
    public object Extension : MutableExtensions.DegradingKey<MediaTypeEncoderRegistry, Map<MediaType, List<MediaTypeEncoder>>> {
        override fun default(): MediaTypeEncoderRegistry = MediaTypeEncoderRegistry()
        override fun MediaTypeEncoderRegistry.include(other: Map<MediaType, List<MediaTypeEncoder>>) {
            for ((type, list) in other) registry.getOrPut(type, ::ArrayList).addAll(list)
        }
    }
}


public class MediaTypeDecoderRegistry(
    private val registry: HashMap<MediaType, ArrayList<MediaTypeDecoder>> = HashMap()
) : Map<MediaType, List<MediaTypeDecoder>> by registry {
    public fun register(decoder: MediaTypeDecoder) {
        registry.getOrPut(decoder.mediaType, ::ArrayList).apply {
            add(decoder)
            sortBy { it.priority }
        }
    }

    // extension key for registration
    public object Extension : MutableExtensions.DegradingKey<MediaTypeDecoderRegistry, Map<MediaType, List<MediaTypeDecoder>>> {
        override fun default(): MediaTypeDecoderRegistry = MediaTypeDecoderRegistry()
        override fun MediaTypeDecoderRegistry.include(other: Map<MediaType, List<MediaTypeDecoder>>) {
            for ((type, list) in other) registry.getOrPut(type, ::ArrayList).addAll(list)
        }
    }
}

public val ServerBuilder.mediaTypeDecoders: MediaTypeDecoderRegistry by MediaTypeDecoderRegistry.Extension
public val ServerDefinition.mediaTypeDecoders: Map<MediaType, List<MediaTypeDecoder>> by MediaTypeDecoderRegistry.Extension

public val ServerBuilder.mediaTypeEncoders: MediaTypeEncoderRegistry by MediaTypeEncoderRegistry.Extension
public val ServerDefinition.mediaTypeEncoders: Map<MediaType, List<MediaTypeEncoder>> by MediaTypeEncoderRegistry.Extension


context(serverRuntime: ServerRuntime)
public suspend fun <T> TypedData.parse(serializer: DeserializationStrategy<T>): T {
    val format = serverRuntime.server.mediaTypeDecoders[mediaType]?.firstOrNull { it.accepts(mediaType.parameters) }
        ?: throw BadRequestException("No media type decoder found supporting $mediaType")
    return format(this, serializer)
}

context(serverRuntime: ServerRuntime)
public suspend fun <T> T.toHttpContent(accepts: List<MediaType>, serializer: SerializationStrategy<T>): TypedData {
    val (type, format) = accepts.firstNotNullOfOrNull { type ->
        serverRuntime.server.mediaTypeEncoders[type]
            ?.firstOrNull { it.accepts(type.parameters) }
            ?.let { type to it }
    } ?: throw BadRequestException("No media type decoder found supporting ${accepts.joinToString()}")
    return format(type, serializer, this)
}

context(server: ServerRuntime)
public fun <T> HttpRequest<*>.queryParameters(serializer: KSerializer<T>): T {
    try {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return server.internalSerialization.formDataFormat.decodeFromMap(
            serializer,
            queryParameters.groupBy { it.first }.mapValues { it.value.joinToString(",") { it.second } }
        )
    } catch (e: SerializationException) {
        throw BadRequestException(
            detail = "serialization",
            message = e.message ?: "Unknown serialization error",
            cause = e.cause
        )
    }
}