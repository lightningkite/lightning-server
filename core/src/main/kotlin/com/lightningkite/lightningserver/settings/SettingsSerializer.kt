package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import java.io.File

/**
 * Custom KotlinX Serialization serializer for server settings.
 *
 * This serializer enables settings to be loaded from and saved to configuration files
 * by dynamically building a class descriptor based on the available settings. Each setting
 * becomes a property in the serialized structure.
 *
 * **Special Features:**
 * - Dynamically generates a descriptor with one element per setting
 * - Marks optional settings as optional in the descriptor
 * - Supports a special `defaults` property to chain configuration files (JSON only)
 * - Settings from a defaults file are loaded with lower priority
 *
 * **Defaults File Example:**
 * ```json
 * {
 *   "defaults": "~/shared-config.json",
 *   "webUrl": "http://localhost:8080"
 * }
 * ```
 * Values in the main file override values from the defaults file.
 *
 * @property keys Ordered list of settings to serialize/deserialize
 * @property module SerializersModule for custom type serialization
 * @property relativeTo Base directory for resolving relative paths in defaults property (null disables defaults)
 * @property traversed Set of files already visited in the defaults chain (for circular dependency detection)
 */
@InternalLightningServerApi
public class SettingsSerializer(private val keys: List<ServerSetting<*, *>>, private val module: SerializersModule, private val relativeTo: File?, private val traversed: Set<File> = setOf()) :
    KSerializer<Map<ServerSetting<*, *>, Any?>> {
    /**
     * The serialization descriptor for settings.
     *
     * Dynamically creates a class descriptor with:
     * - One element per setting (using the setting's name and serializer descriptor)
     * - Optional flag set appropriately for each setting
     * - An additional optional `defaults` string element for file chaining
     */
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.lightningkite.lightningserver.settings.Settings${keys.sumOf { it.name.hashCode() }}") {
            for (key in keys) element(
                key.name,
                key.serializer.descriptor,
                isOptional = key.optional,
            )
            element("defaults", String.serializer().descriptor, isOptional = true)
        }

    /**
     * Serializes settings to the output format.
     *
     * Only settings present in the map are serialized; missing settings are omitted.
     * The `defaults` property is never serialized.
     *
     * @param encoder The encoder to write to
     * @param value Map of settings to their serializable values
     */
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

    /**
     * Deserializes settings from the input format.
     *
     * This function handles:
     * 1. Regular setting values, which are added to the result map
     * 2. The special `defaults` property (if present), which loads values from another JSON file
     * 3. Priority merging: values from the main file override values from the defaults file
     *
     * **Tilde Expansion**: The `~` character in the defaults path is expanded to the user's home directory.
     *
     * @param decoder The decoder to read from
     * @return A map of settings to their deserialized values
     */
    override fun deserialize(decoder: Decoder): Map<ServerSetting<*, *>, Any?> {
        val lowPriorityMap = HashMap<ServerSetting<*, *>, Any?>()
        val map = HashMap<ServerSetting<*, *>, Any?>()
        decoder.beginStructure(descriptor).apply {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index == CompositeDecoder.UNKNOWN_NAME) continue
                if (index == keys.size) {
                    // Handle the "defaults" property
                    if(relativeTo == null) throw SerializationException("Defaults file usage is disabled.")
                    val f = relativeTo.resolve(decodeStringElement(descriptor, index).replace("~", System.getProperty("user.home")))
                    if(f in traversed) throw SerializationException("Circular defaults chain detected: ${traversed.joinToString(" -> ")} -> ${f.absolutePath}")
                    if(!f.exists()) throw SerializationException("Defaults file '${f.absolutePath}' does not exist.")
                    val format = settingsFormat(f.extension, module)
                    lowPriorityMap += format.decodeFromString(SettingsSerializer(keys, module, f.parentFile ?: File("."), traversed = traversed + f), f.readText())
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
