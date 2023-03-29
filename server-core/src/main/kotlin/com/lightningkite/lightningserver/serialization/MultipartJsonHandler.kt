package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningdb.LazyRenamedSerialDescriptor
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.files.resolveRandom
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.toMultipartContent
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.*

class MultipartJsonHandler(val json: () -> Json) : Serialization.HttpContentParser {
    override val contentType: ContentType = ContentType.MultiPart.FormData

    companion object {
        private const val multipartJsonKey = "__json"
    }

    override suspend fun <T> invoke(content: HttpContent, serializer: KSerializer<T>): T {
        val multipart = content as? HttpContent.Multipart ?: content.stream().toMultipartContent(content.type)
        val mainData = HashMap<String, Any?>()
        val overrideData = HashMap<String, Any?>()
        var baselineJson: JsonElement = JsonNull
        multipart.parts.collect { part ->
            when (part) {
                is HttpContent.Multipart.Part.FormItem -> {
                    if (part.key == multipartJsonKey) {
                        baselineJson = json().parseToJsonElement(part.value)
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
                        contentType.extension ?: part.filename.substringAfterLast(".")
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
        return if (baselineJson is JsonObject) {
            baselineJson.jsonObject.writeInto(mainData)
            mainData.putAll(overrideData)
            json().decodeFromJsonElement(serializer, mainData.toJsonObject())
        } else throw BadRequestException("")
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
}