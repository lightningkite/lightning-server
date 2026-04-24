@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * StringFormat for encoding/decoding application/x-www-form-urlencoded data.
 *
 * This format handles URL-encoded form data commonly used in HTML form submissions
 * and query parameters. It supports both URL-encoded strings and structured
 * key-value representations.
 *
 * **Important gotcha:** Primitive types and enums are automatically wrapped in a box
 * object because the underlying Properties format requires structure-kind descriptors.
 * This wrapping is transparent to users but may affect performance for simple types.
 *
 * @property serializersModule The serializers module for handling custom types
 */
public class FormDataFormat(override val serializersModule: SerializersModule) : StringFormat {
    private val properties = Properties(serializersModule)

    /**
     * Decodes a URL-encoded form string into a Kotlin object.
     *
     * Example: "name=John&age=30" -> User(name="John", age=30)
     *
     * @param deserializer The deserialization strategy
     * @param string The URL-encoded form data string
     * @return The decoded object
     */
    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        return decodeFromMap(deserializer, string.split('&').associate {
            URLDecoder.decode(
                it.substringBefore('='),
                Charsets.UTF_8
            ) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        })
    }

    /**
     * Encodes a Kotlin object into a URL-encoded form string.
     *
     * Example: User(name="John", age=30) -> "name=John&age=30"
     *
     * @param serializer The serialization strategy
     * @param value The value to encode
     * @return The URL-encoded form data string
     */
    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
        return encodeToMap(serializer, value).entries.joinToString("&") {
            URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(
                it.value,
                Charsets.UTF_8
            )
        }
    }

    /**
     * Decodes a list of key-value pairs into a Kotlin object.
     *
     * This is useful when working with parsed query parameters or form data
     * that's already been split but not URL-decoded.
     *
     * @param deserializer The deserialization strategy
     * @param string The list of key-value pairs
     * @return The decoded object
     */
    public fun <T> decodeFromList(deserializer: DeserializationStrategy<T>, string: List<Pair<String, String>>): T {
        return decodeFromMap(deserializer, string.associate { it.first to it.second })
    }

    /**
     * Encodes a Kotlin object into a list of key-value pairs.
     *
     * @param serializer The serialization strategy
     * @param value The value to encode
     * @return The list of key-value pairs
     */
    public fun <T> encodeToList(serializer: SerializationStrategy<T>, value: T): List<Pair<String, String>> {
        return encodeToMap(serializer, value).entries.map { it.key to it.value }
    }

    /**
     * Decodes a map of strings into a Kotlin object.
     *
     * Primitive types and enums are automatically wrapped to meet the requirements
     * of the underlying Properties format.
     *
     * @param deserializer The deserialization strategy
     * @param strings The map of string key-value pairs
     * @return The decoded object
     */
    public fun <T> decodeFromMap(deserializer: DeserializationStrategy<T>, strings: Map<String, String>): T {
        return if (deserializer.descriptor.needsWrapping(this.serializersModule)) {
            properties.decodeFromStringMap(WrappingBox.serializer(deserializer as KSerializer<T>), strings).value
        } else {
            properties.decodeFromStringMap(deserializer, strings)
        }
    }

    /**
     * Encodes a Kotlin object into a map of strings.
     *
     * Primitive types and enums are automatically wrapped to meet the requirements
     * of the underlying Properties format.
     *
     * @param serializer The serialization strategy
     * @param value The value to encode
     * @return The map of string key-value pairs
     */
    public fun <T> encodeToMap(serializer: SerializationStrategy<T>, value: T): Map<String, String> {
        return if (serializer.descriptor.needsWrapping(this.serializersModule)) {
            properties.encodeToStringMap(WrappingBox.serializer(serializer as KSerializer<T>), WrappingBox(value))
        } else {
            properties.encodeToStringMap(serializer, value)
        }
    }

    /**
     * Internal wrapper used to box primitive types and enums for Properties format compatibility.
     */
    @Serializable
    private data class WrappingBox<T>(val value: T)

    /**
     * Determines if a type needs wrapping based on its serial descriptor.
     *
     * Primitive types and enums require wrapping because Properties format
     * only works with structure-kind descriptors at the root level.
     */
    private fun SerialDescriptor.needsWrapping(module: SerializersModule): Boolean = when (kind) {
        is PrimitiveKind -> true
        SerialKind.ENUM -> true
        is StructureKind -> false
        PolymorphicKind.OPEN -> false
        PolymorphicKind.SEALED -> false
        SerialKind.CONTEXTUAL -> (module.getContextualDescriptor(this)
            ?: throw IllegalArgumentException("No contextual serializer found for '$serialName'")).needsWrapping(module)
    }

}