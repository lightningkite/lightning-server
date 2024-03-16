package com.lightningkite.lightningserver.serialization

import io.ktor.http.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.modules.SerializersModule

class FormDataFormat(val stringDeferringConfig: StringDeferringConfig) : StringFormat {
    override val serializersModule: SerializersModule
        get() = stringDeferringConfig.serializersModule

    override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
        return StringDeferringDecoder(
            config = stringDeferringConfig,
            descriptor = deserializer.descriptor,
            map = string.split('&').associate {
                it.substringBefore('=').decodeURLQueryComponent() to it.substringAfter('=').decodeURLQueryComponent()
            }
        ).decodeSerializableValue(deserializer)
    }

    override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
        return StringDeferringEncoder(stringDeferringConfig, steadyHeaders = false).apply {
            encodeSerializableValue(
                serializer,
                value
            )
        }.map.entries.joinToString("&") { "${it.key.encodeURLParameter()}=${it.value.encodeURLParameter()}" }
    }
}