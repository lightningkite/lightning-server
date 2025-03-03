package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.serializer
import java.net.URLDecoder
import java.net.URLEncoder

inline fun <reified T> Properties.decodeFromFormData(value: String): T =
    decodeFromFormData(serializersModule.serializer<T>(), value)

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

fun <T> Properties.decodeFromFormData(serializer: DeserializationStrategy<T>, value: String): T {
    if(serializer.descriptor.needsWrapping(this.serializersModule)) {
        return decodeFromStringMap<WrappingBox<T>>(
            WrappingBox.serializer(serializer as KSerializer<T>),
            value.split('&').associate {
                URLDecoder.decode(
                    it.substringBefore('='),
                    Charsets.UTF_8
                ) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
        ).value
    } else {
        return decodeFromStringMap<T>(
            serializer,
            value.split('&').associate {
                URLDecoder.decode(
                    it.substringBefore('='),
                    Charsets.UTF_8
                ) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
        )
    }
}

inline fun <reified T> Properties.encodeToFormData(value: T): String =
    encodeToFormData(serializersModule.serializer<T>(), value)

fun <T> Properties.encodeToFormData(serializer: SerializationStrategy<T>, value: T): String {
    if(serializer.descriptor.needsWrapping(this.serializersModule)) {
        return encodeToStringMap<WrappingBox<T>>(
            WrappingBox.serializer(serializer as KSerializer<T>),
            WrappingBox(value)
        ).entries.joinToString("&") {
            URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(
                it.value,
                Charsets.UTF_8
            )
        }
    } else {
        return encodeToStringMap<T>(
            serializer,
            value
        ).entries.joinToString("&") {
            URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(
                it.value,
                Charsets.UTF_8
            )
        }
    }
}