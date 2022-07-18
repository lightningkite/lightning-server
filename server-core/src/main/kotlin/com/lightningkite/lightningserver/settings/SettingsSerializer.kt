package com.lightningkite.lightningserver.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import java.util.concurrent.ConcurrentHashMap

object SettingsSerializer : KSerializer<Settings> {
    val parts = Settings.requirements.values.sortedBy { it.name }
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Settings") {
        for (setting in parts) {
            element(setting.name, setting.serializer.descriptor, isOptional = true)
        }
    }

    override fun deserialize(decoder: Decoder): Settings {
        val map = HashMap<String, Any?>()
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index == CompositeDecoder.UNKNOWN_NAME) continue
                val setting = parts[index]
                @Suppress("UNCHECKED_CAST")
                map[setting.name] = decodeSerializableElement(descriptor, index, setting.serializer)
            }
        }
        Settings.populate(map)
        return Settings
    }

    override fun serialize(encoder: Encoder, value: Settings) {
        encoder.encodeStructure(descriptor) {
            val values = Settings.current()
            for ((index, setting) in parts.withIndex()) {
                @Suppress("UNCHECKED_CAST")
                encodeSerializableElement(
                    descriptor,
                    index,
                    setting.serializer as KSerializer<Any?>,
                    values[setting.name]
                )
            }
        }
    }
}