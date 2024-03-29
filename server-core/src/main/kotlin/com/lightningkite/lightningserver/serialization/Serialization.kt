package com.lightningkite.lightningserver.serialization

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.jershell.kbson.*
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.files.ExternalServerFileSerializer
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.csv.Csv
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.properties.Properties
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
import nl.adaptivity.xmlutil.serialization.XML
import java.math.BigDecimal
import java.time.*
import java.util.*
import kotlin.collections.HashMap

/**
 * A place to hold all the support Serialization types.
 */
abstract class Serialization {
    companion object : Serialization() {
        override fun defaultModule(): SerializersModule =
            ClientModule.overwriteWith(serializersModuleOf(ExternalServerFileSerializer))
                .overwriteWith(additionalModule)
    }

    object Internal : Serialization() {
        override fun defaultModule(): SerializersModule = ClientModule.overwriteWith(additionalModule)
    }

    var additionalModule: SerializersModule by SetOnce { SerializersModule { } }
    protected abstract fun defaultModule(): SerializersModule
    var module: SerializersModule by SetOnce { defaultModule() }
    var json: Json by SetOnce {
        Json {
            ignoreUnknownKeys = true
            serializersModule = module
            encodeDefaults = true
        }
    }
    var jsonWithoutDefaults: Json by SetOnce {
        Json {
            ignoreUnknownKeys = true
            serializersModule = module
            encodeDefaults = false
        }
    }
    var csv: Csv by SetOnce {
        Csv {
            hasHeaderRecord = true
            ignoreUnknownColumns = true
            serializersModule = module
            trimUnquotedWhitespace = true
            deferToFormatWhenVariableColumns = json
        }
    }
    val yaml: Yaml by SetOnce {
        Yaml(module, YamlConfiguration(encodeDefaults = false, strictMode = false))
    }
    var bson: KBson by SetOnce {
        KBson(module.overwriteWith(BsonOverrides), Configuration())
    }
    var xml: XML by SetOnce {
        XML(module) {
            this.unknownChildHandler = UnknownChildHandler { input, inputKind, descriptor, name, candidates ->
                emptyList()
            }
            this.xmlDeclMode = XmlDeclMode.Auto
            this.repairNamespaces = true
        }
    }
    var cbor: Cbor by SetOnce {
        Cbor {
            ignoreUnknownKeys = true
            serializersModule = module
            encodeDefaults = true
        }
    }
    var javaData: JavaData by SetOnce {
        JavaData(module)
    }
    var properties: Properties by SetOnce {
        Properties(module)
    }

    interface HttpContentParser {
        val contentType: ContentType
        suspend operator fun <T> invoke(content: HttpContent, serializer: KSerializer<T>): T
    }

    interface HttpContentEmitter {
        val contentType: ContentType
        suspend operator fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent
        suspend fun <T> streaming(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent =
            invoke(contentType, serializer, value)
    }

    interface HttpContentHandler : HttpContentParser, HttpContentEmitter

    val parsers = HashMap<ContentType, HttpContentParser>()
    val emitters = HashMap<ContentType, HttpContentEmitter>()
    fun handler(handler: HttpContentHandler) {
        parsers[handler.contentType] = handler
        emitters[handler.contentType] = handler
    }

    fun emitter(handler: HttpContentEmitter) {
        emitters[handler.contentType] = handler
    }

    fun parser(handler: HttpContentParser) {
        parsers[handler.contentType] = handler
    }

    private fun KSerializer<*>.isPrimitive(): Boolean {
        var current = this.descriptor
        while (true) {
            when (current.kind) {
                is PrimitiveKind -> return true
                SerialKind.CONTEXTUAL -> current =
                    Serialization.json.serializersModule.getContextualDescriptor(current)!!

                else -> return false
            }
        }
    }

    @kotlinx.serialization.Serializable
    data class IdHolder<ID>(val id: ID)

    fun <T> toString(value: T, serializer: KSerializer<T>): String {
        return Serialization.properties.encodeToStringMap(IdHolder.serializer(serializer), IdHolder(value))["id"]!!
    }

    fun <T> fromString(string: String, serializer: KSerializer<T>): T {
        return Serialization.properties.decodeFromStringMap(IdHolder.serializer(serializer), mapOf("id" to string)).id
    }

    init {
        handler(FormDataHandler { properties })
        handler(JsonFormatHandler(json = { json }, jsonWithoutDefaults = { jsonWithoutDefaults }))
        handler(StringFormatHandler({ csv }, ContentType.Text.CSV))
        handler(BinaryFormatHandler({ cbor }, ContentType.Application.Cbor))
        handler(BinaryFormatHandler({
            object : BinaryFormat {
                override val serializersModule: SerializersModule
                    get() = bson.serializersModule

                override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
                    return bson.load(deserializer, bytes)
                }

                override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
                    return bson.dump(serializer, value)
                }
            }
        }, ContentType.Application.Bson))
        parser(MultipartJsonHandler { json })
    }
}

