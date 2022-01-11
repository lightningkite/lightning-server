package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorkmongo.IsFile
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.apache.commons.vfs2.VFS
import kotlin.io.use

class MultipartJsonConverter(val json: Json): ContentConverter {
    val jsonKey = "__json"
    override suspend fun convertForReceive(context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any? {
        val mainData = HashMap<String, Any?>()
        val serializer = json.serializersModule.serializer(context.subject.typeInfo)
        context.context.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    if(part.name == jsonKey) json.parseToJsonElement(part.value).jsonObject.writeInto(mainData)
                }
                is PartData.BinaryItem -> {
                    if(part.name == jsonKey) json.parseToJsonElement(part.provider().readText()).jsonObject.writeInto(mainData)
                }
                is PartData.FileItem -> {
                    val path = part.name?.split('.') ?: return@forEachPart
                    val isFile = serializer.isFile(path) ?: return@forEachPart
                    part
                        .streamProvider()
                        .use { input ->
                            val manager = withContext(Dispatchers.IO) {
                                @Suppress("BlockingMethodInNonBlockingContext")
                                VFS.getManager()!!
                            }
                            manager.uploadUnique(input, "${isFile.pathPrefix}/${part.originalFileName!!}")
                        }
                        .let {
                            var current: MutableMap<String, Any?> = mainData
                            for(pathPart in path.dropLast(1)) {
                                @Suppress("UNCHECKED_CAST")
                                current = current[pathPart] as? MutableMap<String, Any?> ?: HashMap()
                            }
                            current[path.last()] = JsonPrimitive(it.publicUrl)
                        }
                }
            }
        }
        return json.decodeFromJsonElement(serializer, mainData.toJsonObject())
    }

    override suspend fun convertForSend(
        context: PipelineContext<Any, ApplicationCall>,
        contentType: ContentType,
        value: Any
    ): Any? = throw NotImplementedError()
}

@OptIn(ExperimentalSerializationApi::class)
private fun KSerializer<*>.isFile(parts: List<String>): IsFile? {
    var current = this.descriptor
    for(part in parts.dropLast(1)) {
        val index = current.getElementIndex(part)
        if(index == CompositeDecoder.UNKNOWN_NAME) return null
        current = current.getElementDescriptor(index)
    }
    val index = current.getElementIndex(parts.last())
    if(index == CompositeDecoder.UNKNOWN_NAME) return null
    val final = current.getElementAnnotations(index)
    return final.asSequence().filterIsInstance<IsFile>().firstOrNull()
}

@Suppress("UNCHECKED_CAST")
private fun JsonObject.writeInto(map: MutableMap<String, Any?> = HashMap()): MutableMap<String, Any?> {
    for((key, value) in this) {
        map[key] = when(value) {
            is JsonObject -> value.writeInto(map[key] as? HashMap<String, Any?> ?: HashMap())
            else -> value
        }
    }
    return map
}

private fun Map<String, Any?>.toJsonObject(): JsonObject = buildJsonObject {
    for((key, value) in this@toJsonObject) {
        @Suppress("UNCHECKED_CAST")
        put(key, when(value) {
            is JsonElement -> value
            is Map<*, *> -> (value as Map<String, Any?>).toJsonObject()
            else -> throw NotImplementedError()
        })
    }
}