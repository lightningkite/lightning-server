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
                    return properties.decodeFromStringMap(
                        deserializer,
                        string.lines().map { it.substringBefore('#').trim() }.filter { it.isNotBlank() }.associate {
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

/*
 * TODO: API Recommendations for ServerSettings.ext.kt
 *
 * 1. **POTENTIAL ISSUE**: Properties format parsing uses `substringAfter('=')` which only works
 *    for simple values. If a property value contains '=' (like a URL or connection string), only
 *    the first part is kept. Should use `substringAfter('=', missingDelimiterValue = "")` or similar.
 *
 * 2. **POTENTIAL ISSUE**: Properties parsing treats '#' as comment anywhere on line, but doesn't
 *    handle escaped '#'. A value like "color=#FF0000" would be truncated. Need proper escaping.
 *
 * 3. The file extension check for properties format only matches exactly "properties". Files like
 *    "config.props" or "settings.property" won't be detected. Consider contains() or regex.
 *
 * 4. loadFromFile() auto-generates a settings file with defaults if it doesn't exist, then throws
 *    MissingSettingFile. This is a good workflow but could be surprising - some might expect it
 *    to succeed using the defaults. Document this behavior prominently.
 *
 * 5. The decryption feature reads from environment variable but doesn't validate the password
 *    strength or check if decryption actually succeeded (could return garbage). Add validation.
 *
 * 6. When missing required settings, a "suggested" file is created but there's no indication of
 *    what changed between the original and suggested files. Consider generating a diff or
 *    annotating which settings were missing.
 *
 * 7. The "defaults" property feature (referencing another JSON file) is documented but not shown
 *    in this file. This feature appears to be implemented in SettingsSerializer.kt. Consider
 *    adding cross-reference docs or moving logic here.
 *
 * 8. No validation that the file isn't too large or contains malicious content. Consider adding
 *    size limits or sandboxing for untrusted settings files.
 *
 * 9. The settingsFormat function returns EmptySerializersModule for properties format but uses
 *    the provided module for actual serialization. This inconsistency is confusing.
 */