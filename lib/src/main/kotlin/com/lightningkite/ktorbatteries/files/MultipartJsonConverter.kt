package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorkmongo.IsFile
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope.coroutineContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.apache.commons.vfs2.VFS
import kotlin.io.use

class MultipartJsonConverter(val json: Json) : ContentConverter {
    val jsonKey = "__json"
    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        val mainData = HashMap<String, Any?>()
        val serializer = json.serializersModule.serializer(typeInfo.reifiedType)
        multiPartData(ContentType.MultiPart.FormData, null, content).forEachPart { part ->
            when (part) {
                is PartData.BinaryChannelItem -> {
                    part.provider().discard()
                }
                is PartData.FormItem -> {
                    if (part.name == jsonKey) json.parseToJsonElement(part.value).jsonObject.writeInto(mainData)
                }
                is PartData.BinaryItem -> {
                    if (part.name == jsonKey) json.parseToJsonElement(part.provider().readText()).jsonObject.writeInto(
                        mainData
                    )
                }
                is PartData.FileItem -> {
                    val path = part.name?.split('.') ?: return@forEachPart
                    val isFile = serializer.isFile(path) ?: return@forEachPart
                    if (part.contentType == null) throw BadRequestException("Content type not provided for uploaded file")
                    if (
                        isFile.allowedTypes
                            .asSequence()
                            .map { ContentType.parse(it) }
                            .none { part.contentType!!.match(it) }
                    ) {
                        throw BadRequestException("Content type ${part.contentType} doesn't match any of the accepted types: ${isFile.allowedTypes.joinToString()}")
                    }
                    part
                        .streamProvider()
                        .use { input ->
                            val manager = files()
                            manager.uploadUnique(
                                input,
                                "${FilesSettings.instance.storageUrl}${FilesSettings.instance.userContentPath}${isFile.pathPrefix}/${part.originalFileName!!}"
                            )
                        }
                        .let {
                            var current: MutableMap<String, Any?> = mainData
                            for (pathPart in path.dropLast(1)) {
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

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any
    ): OutgoingContent = throw NotImplementedError()
}

@OptIn(ExperimentalSerializationApi::class)
private fun KSerializer<*>.isFile(parts: List<String>): IsFile? {
    var current = this.descriptor
    for (part in parts.dropLast(1)) {
        val index = current.getElementIndex(part)
        if (index == CompositeDecoder.UNKNOWN_NAME) return null
        current = current.getElementDescriptor(index)
    }
    val index = current.getElementIndex(parts.last())
    if (index == CompositeDecoder.UNKNOWN_NAME) return null
    val final = current.getElementAnnotations(index)
    return final.asSequence().filterIsInstance<IsFile>().firstOrNull()
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

@OptIn(InternalAPI::class)
private fun multiPartData(contentType: ContentType, length: Long? = null, rc: ByteReadChannel): MultiPartData {
    return CIOMultipartDataBase(
        Dispatchers.Unconfined,
        rc,
        contentType.toString(),
        length
    )
}