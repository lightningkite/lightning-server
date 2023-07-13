package com.lightningkite.lightningserver.serialization

import com.lightningkite.khrysalis.fatalError
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer

inline fun <reified T> HttpRequest.queryParameters(): T =
    queryParameters(Serialization.properties.serializersModule.serializer())

fun <T> HttpRequest.queryParameters(serializer: KSerializer<T>): T {
    try {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return Serialization.properties.decodeFromStringMap<T>(
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

suspend inline fun <reified T> HttpContent.parse(): T = parse(Serialization.module.serializer())
suspend fun <T> HttpContent.parse(serializer: KSerializer<T>): T {
    try {
        val parser = Serialization.parsers[this.type]
            ?: throw BadRequestException("Content type $type not accepted; available types are ${Serialization.parsers.keys.joinToString()}")
        return parser(this, serializer)
    } catch (e: SerializationException) {
        throw BadRequestException(
            detail = "serialization",
            message = e.message ?: "Unknown serialization error",
            cause = e.cause
        )
    }
}
suspend fun <T> HttpContent.parseWithDefault(serializer: KSerializer<T>, defaultType: ContentType = ContentType.Application.Json): T {
    try {
        val parser = Serialization.parsers[this.type]
            ?: Serialization.parsers[defaultType]
            ?: throw BadRequestException("Content type $type not accepted; available types are ${Serialization.parsers.keys.joinToString()}")
        return parser(this, serializer)
    } catch (e: SerializationException) {
        throw BadRequestException(
            detail = "serialization",
            message = e.message ?: "Unknown serialization error",
            cause = e.cause
        )
    }
}

suspend inline fun <reified T> T.toHttpContent(acceptedTypes: List<ContentType>): HttpContent? =
    toHttpContent(acceptedTypes, Serialization.module.serializer())

suspend fun <T> T.toHttpContent(acceptedTypes: List<ContentType>, serializer: KSerializer<T>): HttpContent? {
    if (this == Unit) return null
    // Always fall back to Json
    for (contentType in acceptedTypes.plus(ContentType.Application.Json)) {
        Serialization.emitters[contentType]?.let {
            return it(contentType, serializer, this)
        }
    }
    fatalError()
}

