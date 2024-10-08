package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import kotlin.system.exitProcess

/**
 * A helper function for loading in your settings object from the file.
 * If the file does not exist it will use the result from [makeDefaultSettings] to created one then shutdown the process.
 *
 * @param settingsFile the location of the file to decode [SETTINGS] from.
 * @param makeDefaultSettings a lambda to retrieve a default example of [SETTINGS] to encode to a file.
 */
fun loadSettings(settingsFile: File): Settings {
    if (!settingsFile.exists()) {
        val json = Json {
            encodeDefaults = true
            serializersModule = Serialization.module
        }
        Settings.populateDefaults()
        settingsFile.writeText(json.encodeToString(SettingsSerializer(), Settings))

        logger.error("Need a settings file - example generated at ${settingsFile.absolutePath}.")
        exitProcess(1)
    }
    try {
        return Serialization.json.decodeFromStream(SettingsSerializer(), settingsFile.inputStream())
    } catch (e: SerializationException) {
        val json = Json {
            encodeDefaults = true
            serializersModule = Serialization.module
        }
        val suggested =
            settingsFile.absoluteFile.parentFile.resolve(settingsFile.nameWithoutExtension + ".suggested.json")
        logger.error("Settings were incorrect.  Suggested updates are inside ${suggested.absolutePath}.")
        logger.error(e.message)
        Settings.repair()
        suggested.writeText(json.encodeToString(SettingsSerializer(), Settings))
        e.printStackTrace()
        exitProcess(1)
    }
}


