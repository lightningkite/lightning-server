package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties

class FormDataFormat(override val serializersModule: SerializersModule) : StringFormat {
    val properties = Properties(serializersModule)

    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        return properties.decodeFromFormData(deserializer, string)
    }

    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
        return properties.encodeToFormData(serializer, value)
    }
}