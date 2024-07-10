package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningdb.LazyRenamedSerialDescriptor
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.files.resolveRandom
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpHeader
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
            val key = part.headers.getValues(HttpHeader.ContentDisposition).firstOrNull() ?: return@collect
            if (key.root == multipartJsonKey) {
                baselineJson = json().parseToJsonElement(part.content.text())
            } else {
                val filename = key.parameters["filename"]
                if (filename.isNullOrBlank()) return@collect
                val path = key.root.split('.')
                if (!serializer.isFile(path)) throw BadRequestException("$key is not a ServerFile.")
                val file = ExternalServerFileSerializer.uploadFile(part.content)
                var current: MutableMap<String, Any?> = overrideData
                for (pathPart in path.dropLast(1)) {
                    @Suppress("UNCHECKED_CAST")
                    current =
                        current.getOrPut(pathPart) { HashMap<String, Any?>() } as MutableMap<String, Any?>
                }
                current[path.last()] = JsonPrimitive(file.signedUrl)
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