package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

public class SettingsSerializer(private val keys: List<ServerSetting<*, *>>, private val chainFormat: StringFormat) :
    KSerializer<Map<ServerSetting<*, *>, Any?>> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.lightningkite.lightningserver.settings.Settings${keys.hashCode()}") {
            for (key in keys) element(
                key.name,
                key.serializer.descriptor,
                isOptional = key.optional,
            )
            element("defaults", String.serializer().descriptor, isOptional = true)
        }

    override fun serialize(
        encoder: Encoder,
        value: Map<ServerSetting<*, *>, Any?>,
    ) {
        encoder.beginStructure(descriptor).apply {
            for (key in keys) {
                if (key in value) {
                    @Suppress("UNCHECKED_CAST")
                    encodeSerializableElement(
                        descriptor,
                        descriptor.getElementIndex(key.name),
                        key.serializer as KSerializer<Any?>,
                        value[key]
                    )
                }
            }
            endStructure(descriptor)
        }
    }

    override fun deserialize(decoder: Decoder): Map<ServerSetting<*, *>, Any?> {
        val lowPriorityMap = HashMap<ServerSetting<*, *>, Any?>()
        val map = HashMap<ServerSetting<*, *>, Any?>()
        decoder.beginStructure(descriptor).apply {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index == CompositeDecoder.UNKNOWN_NAME) continue
                if (index == keys.size) {
                    val f = File(decodeStringElement(descriptor, index).replace("~", System.getProperty("user.home")))
                    when (f.extension) {
                        "json" -> (chainFormat as Json).let { json ->
                            json.parseToJsonElement(f.readText())
                                .let { it as JsonObject }
                                .entries.forEach { entry ->
                                    val setting = keys.find { it.name == entry.key } ?: return@forEach
                                    lowPriorityMap[setting] =
                                        json.decodeFromJsonElement(setting.serializer, entry.value)
                                }
                        }
                    }
                } else {
                    val setting = keys[index]
                    @Suppress("UNCHECKED_CAST")
                    map[setting] = decodeSerializableElement(descriptor, index, setting.serializer)
                }
            }
            endStructure(descriptor)
        }
        return lowPriorityMap + map
    }
}