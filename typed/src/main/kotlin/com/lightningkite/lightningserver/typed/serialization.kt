package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ServerDefinition
import com.lightningkite.lightningserver.ServerRunning
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlin.collections.List

public val ServerDefinition.mediaTypeDecoders: Map<MediaType, List<MediaTypeDecoder>>
    get() = get(MediaTypeDecoderList) ?: mapOf()
public val ServerDefinition.mediaTypeEncoders: Map<MediaType, List<MediaTypeEncoder>>
    get() = get(MediaTypeEncoderList) ?: mapOf()
public fun ServerDefinition.register(decoder: MediaTypeDecoder): Unit {
    val resultingList = (get(MediaTypeDecoderList)?.get(decoder.mediaType) ?: listOf())
        .plus(decoder).sortedBy { it.priority }
    set(MediaTypeDecoderList, (get(MediaTypeDecoderList) ?: mapOf()) + (decoder.mediaType to resultingList))
}
public fun ServerDefinition.register(encoder: MediaTypeEncoder): Unit {
    val resultingList = (get(MediaTypeEncoderList)?.get(encoder.mediaType) ?: listOf())
        .plus(encoder).sortedBy { it.priority }
    set(MediaTypeEncoderList, (get(MediaTypeEncoderList) ?: mapOf()) + (encoder.mediaType to resultingList))
}
public fun ServerDefinition.register(coder: MediaTypeCoder): Unit {
    register(coder as MediaTypeEncoder)
    register(coder as MediaTypeDecoder)
}

public object MediaTypeDecoderList: ServerDefinition.ExtensionKey<Map<MediaType, List<MediaTypeDecoder>>>
public object MediaTypeEncoderList: ServerDefinition.ExtensionKey<Map<MediaType, List<MediaTypeEncoder>>>



context(serverRunning: ServerRunning)
public suspend fun <T> TypedData.parse(serializer: DeserializationStrategy<T>): T {
    val format = serverRunning.server.mediaTypeDecoders[mediaType]?.firstOrNull { it.accepts(mediaType.parameters) }
        ?: throw BadRequestException("No media type decoder found supporting $mediaType")
    return format(this, serializer)
}
context(serverRunning: ServerRunning)
public suspend fun <T> T.toHttpContent(accepts: List<MediaType>, serializer: SerializationStrategy<T>): TypedData {
    val (type, format) = accepts.firstNotNullOfOrNull {
        serverRunning.server.mediaTypeEncoders[it]?.firstOrNull { it.accepts(it.mediaType.parameters) }?.let { f ->
            it to f
        }
    } ?: throw BadRequestException("No media type decoder found supporting ${accepts.joinToString()}")
    return format(type, serializer, this)
}