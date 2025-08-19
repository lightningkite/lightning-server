package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties
import java.net.URLDecoder
import java.net.URLEncoder

public class FormDataFormat(override val serializersModule: SerializersModule) : StringFormat {
    private val properties = Properties(serializersModule)

    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        return decodeFromMap(deserializer, string.split('&').associate {
            URLDecoder.decode(
                it.substringBefore('='),
                Charsets.UTF_8
            ) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        })
    }

    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
        return encodeToMap(serializer, value).entries.joinToString("&") {
            URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(
                it.value,
                Charsets.UTF_8
            )
        }
    }

    public fun <T> decodeFromList(deserializer: DeserializationStrategy<T>, string: List<Pair<String, String>>): T {
        return decodeFromMap(deserializer, string.associate { it.first to it.second })
    }

    public fun <T> encodeToList(serializer: SerializationStrategy<T>, value: T): List<Pair<String, String>> {
        return encodeToMap(serializer, value).entries.map { it.key to it.value }
    }

    public fun <T> decodeFromMap(deserializer: DeserializationStrategy<T>, strings: Map<String, String>): T {
        return if(deserializer.descriptor.needsWrapping(this.serializersModule)) {
            properties.decodeFromStringMap(WrappingBox.serializer(deserializer as KSerializer<T>), strings).value
        } else {
            properties.decodeFromStringMap(deserializer, strings)
        }
    }

    public fun <T> encodeToMap(serializer: SerializationStrategy<T>, value: T): Map<String, String> {
        return if(serializer.descriptor.needsWrapping(this.serializersModule)) {
            properties.encodeToStringMap(WrappingBox.serializer(serializer as KSerializer<T>), WrappingBox(value))
        } else {
            properties.encodeToStringMap(serializer, value)
        }
    }

    @Serializable
    private data class WrappingBox<T>(val value: T)

    private fun SerialDescriptor.needsWrapping(module: SerializersModule): Boolean = when(kind) {
        is PrimitiveKind -> true
        SerialKind.ENUM -> true
        is StructureKind -> false
        PolymorphicKind.OPEN -> false
        PolymorphicKind.SEALED -> false
        SerialKind.CONTEXTUAL -> module.getContextualDescriptor(this)!!.needsWrapping(module)
    }

}