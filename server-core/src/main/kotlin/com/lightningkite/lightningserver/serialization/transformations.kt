package com.lightningkite.lightningserver.serialization

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


private const val multipartJsonKey = "__json"

inline fun <reified T> HttpRequest.queryParameters(): T =
    queryParameters(Serialization.properties.serializersModule.serializer())

fun <T> HttpRequest.queryParameters(serializer: KSerializer<T>): T {
    @Suppress("UNCHECKED_CAST")
    if (serializer == Unit.serializer()) return Unit as T
    return Serialization.properties.decodeFromStringMap<T>(
        serializer,
        queryParameters.groupBy { it.first }.mapValues { it.value.joinToString(",") }
    )
}

suspend inline fun <reified T> HttpContent.parse(): T = parse(Serialization.module.serializer())
suspend fun <T> HttpContent.parse(serializer: KSerializer<T>): T {
    try {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return when (this.type) {
            ContentType.Application.Json -> when (val body = this) {
                is HttpContent.Text -> Serialization.json.decodeFromString(serializer, body.string)
                is HttpContent.Binary -> Serialization.json.decodeFromString(
                    serializer,
                    body.bytes.toString(Charsets.UTF_8)
                )
                is HttpContent.Multipart -> throw BadRequestException("Expected JSON, but got a multipart body.")
                else -> withContext(Dispatchers.IO) {
                    Serialization.json.decodeFromStream(
                        serializer,
                        body.stream()
                    )
                }
            }
            ContentType.Text.CSV -> when (val body = this) {
                is HttpContent.Text -> Serialization.csv.decodeFromString(serializer, body.string)
                is HttpContent.Binary -> Serialization.csv.decodeFromString(
                    serializer,
                    body.bytes.toString(Charsets.UTF_8)
                )
                is HttpContent.Multipart -> throw BadRequestException("Expected JSON, but got a multipart body.")
                else -> withContext(Dispatchers.IO) {
                    Serialization.csv.decodeFromString(
                        serializer,
                        stream().bufferedReader().readText()
                    )
                }
            }
            ContentType.Application.Bson -> when (val body = this) {
                is HttpContent.Text -> Serialization.bson.load(serializer, body.string.toByteArray())
                is HttpContent.Binary -> Serialization.bson.load(serializer, body.bytes)
                is HttpContent.Multipart -> throw BadRequestException("Expected JSON, but got a multipart body.")
                else -> withContext(Dispatchers.IO) {
                    Serialization.bson.load(
                        serializer,
                        stream().readBytes()
                    )
                }
            }
            ContentType.Application.Cbor -> when (val body = this) {
                is HttpContent.Text -> Serialization.cbor.decodeFromByteArray(serializer, body.string.toByteArray())
                is HttpContent.Binary -> Serialization.cbor.decodeFromByteArray(serializer, body.bytes)
                is HttpContent.Multipart -> throw BadRequestException("Expected JSON, but got a multipart body.")
                else -> withContext(Dispatchers.IO) {
                    Serialization.cbor.decodeFromByteArray(
                        serializer,
                        body.stream().readBytes()
                    )
                }
            }
            ContentType.MultiPart.FormData -> {
                val multipart = this as? HttpContent.Multipart ?: this.stream().toMultipartContent(this.type)
                val mainData = HashMap<String, Any?>()
                val overrideData = HashMap<String, Any?>()
                var baselineJson: JsonElement = JsonNull
                multipart.parts.collect { part ->
                    when (part) {
                        is HttpContent.Multipart.Part.FormItem -> {
                            if (part.key == multipartJsonKey) {
                                baselineJson = Serialization.json.parseToJsonElement(part.value)
                            }
                        }
                        is HttpContent.Multipart.Part.DataItem -> {
                            if (part.filename.isBlank()) return@collect
                            val path = part.key.split('.')
                            if (!serializer.isFile(path)) throw BadRequestException("${part.key} is not a ServerFile.")
                            if (part.headers.contentType == null) throw BadRequestException("Content type not provided for uploaded file")
                            //if (
                            //    isFile.allowedTypes
                            //        .asSequence()
                            //        .map { ContentType.parse(it) }
                            //        .none { part.contentType!!.match(it) }
                            //) {
                            //    throw BadRequestException("Content type ${part.contentType} doesn't match any of the accepted types: ${isFile.allowedTypes.joinToString()}")
                            //}
                            val file = ExternalServerFileSerializer.fileSystem().root.resolveRandom(
                                "uploaded/${part.filename.substringBeforeLast(".")}",
                                type.extension ?: part.filename.substringAfterLast(".")
                            )
                            file.write(part.content)
                            var current: MutableMap<String, Any?> = overrideData
                            for (pathPart in path.dropLast(1)) {
                                @Suppress("UNCHECKED_CAST")
                                current =
                                    current.getOrPut(pathPart) { HashMap<String, Any?>() } as MutableMap<String, Any?>
                            }
                            current[path.last()] = JsonPrimitive(file.signedUrl)
                        }
                    }
                }
                if (baselineJson is JsonObject) {
                    baselineJson.jsonObject.writeInto(mainData)
                    mainData.putAll(overrideData)
                    Serialization.json.decodeFromJsonElement(serializer, mainData.toJsonObject())
                } else throw BadRequestException("")
            }
            else -> throw BadRequestException("Content type ${this.type} not supported.")
        }
    } catch (e: SerializationException) {
        throw BadRequestException(e.message, cause = e.cause)
    }
}

suspend inline fun <reified T> T.toHttpContent(acceptedTypes: List<ContentType>): HttpContent? =
    toHttpContent(acceptedTypes, Serialization.module.serializer())

suspend fun <T> T.toHttpContent(acceptedTypes: List<ContentType>, serializer: KSerializer<T>): HttpContent? {
    if (this == Unit) return null
    for (contentType in acceptedTypes) {
        when (contentType) {
            ContentType.Application.Json -> return HttpContent.Text(
                Serialization.json.encodeToString(serializer, this),
                contentType
            )
            ContentType.Text.CSV -> return HttpContent.Text(
                Serialization.csv.encodeToString(serializer, this),
                contentType
            )
            ContentType.Application.Bson -> return HttpContent.Binary(
                Serialization.bson.dump(serializer, this),
                contentType
            )
            ContentType.Application.Cbor -> return HttpContent.Binary(
                Serialization.cbor.encodeToByteArray(
                    serializer,
                    this
                ), contentType
            )
            else -> {}
        }
    }
    return HttpContent.Text(
        Serialization.json.encodeToString(serializer, this),
        ContentType.Application.Json
    )
}


@OptIn(ExperimentalSerializationApi::class)
private fun KSerializer<*>.isFile(parts: List<String>): Boolean {
    var current = this.descriptor
    for (part in parts) {
        val index = current.getElementIndex(part)
        if (index == CompositeDecoder.UNKNOWN_NAME) return false
        current = current.getElementDescriptor(index)
    }
    return current.serialName.removeSuffix("?") == ContextualSerializer(ServerFile::class).descriptor.serialName.removeSuffix(
        "?"
    ) ||
            (current as? LazyRenamedSerialDescriptor)?.getter?.invoke()?.serialName?.removeSuffix("?") == ContextualSerializer(
        ServerFile::class
    ).descriptor.serialName.removeSuffix("?")
}

@Suppress("UNCHECKED_CAST")
private fun JsonObject.writeInto(map: MutableMap<String, Any?> = HashMap()): MutableMap<String, Any?> {
    for ((key, value) in this) {
        map[key] = when (value) {
            is JsonObject -> value.writeInto(map[key] as? HashMap<String, Any?> ?: HashMap())
            else -> value
        }
    }
    return map
}

private fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
    for ((key, value) in this@toJsonObject) {
        @Suppress("UNCHECKED_CAST")
        put(
            key, when (value) {
                is JsonElement -> value
                is Map<*, *> -> (value as Map<String, Any?>).toJsonObject()
                else -> throw NotImplementedError()
            }
        )
    }
}