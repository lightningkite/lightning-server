package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer

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