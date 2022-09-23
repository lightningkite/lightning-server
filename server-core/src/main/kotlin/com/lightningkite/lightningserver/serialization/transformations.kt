package com.lightningkite.lightningserver.serialization

import com.lightningkite.khrysalis.fatalError
import com.lightningkite.lightningdb.LazyRenamedSerialDescriptor
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.files.*
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.toMultipartContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.*

inline fun <reified T> HttpRequest.queryParameters(): T =
    queryParameters(Serialization.properties.serializersModule.serializer())

fun <T> HttpRequest.queryParameters(serializer: KSerializer<T>): T {
    try {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return Serialization.properties.decodeFromStringMap<T>(
            serializer,
            queryParameters.groupBy { it.first }.mapValues { it.value.joinToString(",") }
        )
    } catch (e: SerializationException) {
        throw BadRequestException(e.message, cause = e.cause)
    }
}

suspend inline fun <reified T> HttpContent.parse(): T = parse(Serialization.module.serializer())
suspend fun <T> HttpContent.parse(serializer: KSerializer<T>): T {
    try {
        val parser = Serialization.parsers[this.type]
            ?: throw BadRequestException("Content type $type not accepted; available types are ${Serialization.parsers.keys.joinToString()}")
        return parser(this, serializer)
    } catch (e: SerializationException) {
        throw BadRequestException(e.message, cause = e.cause)
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

