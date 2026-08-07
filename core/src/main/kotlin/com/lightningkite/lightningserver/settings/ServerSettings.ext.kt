@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.data.toJavaFile
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.properties.Properties

/**
 * Context-aware extension to set a setting's serializable value.
 *
 * This allows for a more concise syntax when configuring multiple settings:
 * ```kotlin
 * with(serverSettings) {
 *     myStringSetting set "value"
 *     myIntSetting set 42
 * }
 * ```
 *
 * @param value The serializable value to set
 * @receiver The [ServerSetting] to configure
 * @throws IllegalStateException if settings are already marked as ready
 */
context(builder: ServerSettings)
public infix fun <SERIALIZABLE> ServerSetting<SERIALIZABLE, *>.set(value: SERIALIZABLE) {
    with(builder) { this@set set value }
}

/**
 * Context-aware extension to set a setting's result value directly.
 *
 * This bypasses the normal serialization/deserialization flow and sets the transformed
 * result value directly. This is useful for testing or programmatic configuration where
 * you have the final result value available.
 *
 * ```kotlin
 * with(serverSettings) {
 *     myDatabaseSetting setStatic mockDatabase
 * }
 * ```
 *
 * @param value The result value to set
 * @receiver The [ServerSetting] to configure
 * @throws IllegalStateException if settings are already marked as ready
 */
context(builder: ServerSettings)
public infix fun <RESULT> ServerSetting<*, RESULT>.setStatic(value: RESULT) {
    with(builder) { this@setStatic setStatic value }
}

/**
 * Loads settings from a configuration file (JSON or properties format).
 *
 * This function handles the complete settings loading workflow:
 * 1. Auto-generates the file with defaults if it doesn't exist
 * 2. Decrypts the file if `LIGHTNING_SERVER_SETTINGS_DECRYPTION` environment variable is set
 * 3. Deserializes settings based on file extension (`.json` or `.properties`)
 * 4. Validates that all required (non-optional) settings are present
 * 5. Generates a suggested file with missing settings if validation fails
 *
 * **File Format Detection:**
 * - Files containing `.properties` in the name use Java properties format
 * - All other files use JSON format
 *
 * **Encryption Support:**
 * Set the `LIGHTNING_SERVER_SETTINGS_DECRYPTION` environment variable to the password
 * to automatically decrypt OpenSSL-encrypted settings files.
 *
 * **Properties Format:**
 * ```properties
 * webUrl=http://localhost:8080
 * database.host=localhost
 * database.port=5432
 * ```
 *
 * **JSON Format:**
 * ```json
 * {
 *   "webUrl": "http://localhost:8080",
 *   "database": {
 *     "host": "localhost",
 *     "port": 5432
 *   }
 * }
 * ```
 *
 * **Special Feature - defaults property:**
 * You can reference another JSON file for default values using the `defaults` property:
 * ```json
 * {
 *   "defaults": "~/shared-settings.json",
 *   "webUrl": "http://localhost:8080"
 * }
 * ```
 * Settings in the main file override those in the defaults file.
 *
 * @param file The settings file to load
 * @param module The [SerializersModule] containing serializers for custom types
 * @throws MissingSettingFile if the file doesn't exist (after creating it with defaults)
 * @throws IncompleteSettingsException if required settings are missing (after creating suggested file)
 */
@OptIn(ExperimentalSerializationApi::class, InternalLightningServerApi::class)
public fun ServerSettings.loadFromFile(
    file: KFile,
    module: SerializersModule,
) {
    val format: StringFormat = settingsFormat(file.extension, module)

    val serializer =
        SettingsSerializer((settings - overrides.keys).sortedBy { it.name }, module, file.toJavaFile().parentFile)

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
        if (key !in loaded && !overrides.containsKey(key)) {
            loaded[key] = key.default
            if (!key.optional) {
                missingKeys += key
            }
        }
    }
    if (missingKeys.isNotEmpty()) {
        val suggestedFile = file.withAlteredExtension { "suggested.$it" }
        suggestedFile.writeString(format.encodeToString(serializer, loaded))
        throw IncompleteSettingsException(missingKeys, suggestedFile)
    }
    this.include(loaded)
}

internal fun settingsFormat(extension: String, module: SerializersModule): StringFormat {
    return when (extension) {
        "properties" -> {
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
                    // A '#' only starts a comment when it is the first non-blank character on the
                    // line (standard .properties behavior). Stripping at any '#' would corrupt
                    // values that legitimately contain one, e.g. hex colors or URL fragments.
                    return properties.decodeFromStringMap(
                        deserializer,
                        string.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') }
                            .associate {
                                it.substringBefore('=') to it.substringAfter('=')
                            })
                }
            }
        }

        else -> Json {
            isLenient = true
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            serializersModule = module
        }
    }
}
