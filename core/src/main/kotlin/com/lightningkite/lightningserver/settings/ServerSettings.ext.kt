package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.data.KFile
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties

context(builder: ServerSettings)
public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
    with(builder) { this@set set value }
}

context(builder: ServerSettings)
public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
    with(builder) { this@setStatic setStatic value }
}

@OptIn(ExperimentalSerializationApi::class)
public fun ServerSettings.loadFromFile(
    file: KFile,
    module: SerializersModule,
) {
    val format: StringFormat = if (file.name.contains(".properties")) {
        object : StringFormat {
            val properties = Properties(module)
            override val serializersModule: SerializersModule = EmptySerializersModule()
            override fun <T> encodeToString(
                serializer: SerializationStrategy<T>,
                value: T,
            ): String {
                return properties.encodeToStringMap(serializer, value).entries.joinToString("\n") {
                    "${it.key}=${it.value}"
                }
            }

            override fun <T> decodeFromString(
                deserializer: DeserializationStrategy<T>,
                string: String,
            ): T {
                return properties.decodeFromStringMap(
                    deserializer,
                    string.lines().filter { !it.startsWith("#") }.associate {
                        it.substringBefore('=') to it.substringAfter('=')
                    })
            }
        }
    } else Json {
        isLenient = true
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        serializersModule = module
    }

    val serializer = SettingsSerializer(settings.sortedBy { it.name }, format)

    if (!file.exists()) {
        file.writeString(format.encodeToString(serializer, settings.associateWith { it.default }))
        throw MissingSettingFile(file)
    }

    val bytes = file.readByteArray()
    val decryptedBytes = System.getenv("LIGHTNING_SERVER_SETTINGS_DECRYPTION")
        ?.takeIf { it.isNotBlank() }
        ?.let { sha256Password ->
            OpenSsl.decryptAesCbcPkcs5Sha256(bytes, sha256Password.toByteArray())
        }
        ?: bytes

    val text = decryptedBytes.decodeToString()
    val loaded: MutableMap<ServerSetting<*, *>, Any?> = format.decodeFromString(serializer, text).toMutableMap()
    val missingKeys = HashSet<ServerSetting<*, *>>()
    for (key in settings) {
        if (key !in loaded) {
            loaded[key] = key.default
            if (!key.optional) {
                missingKeys += key
            }
        }
    }
    if (missingKeys.isNotEmpty()) {
        val suggestedFile =
            file.parent!!.resolve(file.nameWithoutExtension.replace(".enc", "") + ".suggested." + file.extension)

        suggestedFile.writeString(format.encodeToString(serializer, loaded))
        throw IncompleteSettingsException(missingKeys, suggestedFile)
    }
    this.include(loaded)
}
