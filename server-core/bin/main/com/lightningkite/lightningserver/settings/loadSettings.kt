package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.properties.decodeFromStringMap
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
        settingsFile.writeText(json.encodeToString(Settings))

        println("Need a settings file - example generated at ${settingsFile.absolutePath}.")
        exitProcess(1)
    }
    try {
        return Serialization.json.decodeFromStream(settingsFile.inputStream())
    } catch (e: Exception) {
        val json = Json {
            encodeDefaults = true
            serializersModule = Serialization.module
        }
        val suggested = settingsFile.absoluteFile.parentFile.resolve(settingsFile.nameWithoutExtension + ".suggested.json")
        println("Settings were incorrect.  Suggested updates are inside ${suggested.absolutePath}.")
        Settings.populateDefaults()
        suggested.writeText(json.encodeToString(Settings))
        e.printStackTrace()
        exitProcess(1)
    }
}


