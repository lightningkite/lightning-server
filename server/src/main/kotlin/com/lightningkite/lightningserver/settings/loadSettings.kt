package com.lightningkite.lightningserver.settings

import com.charleskorn.kaml.Yaml
import com.lightningkite.lightningdb.ClientModule
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.properties.decodeFromStringMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
        Settings.populateDefaults()
        settingsFile.writeText(
            Yaml().encodeToString(Settings)
        )

        println("Need a settings file - example generated at ${settingsFile.absolutePath}.")
        exitProcess(1)
    }
    try {
        return Yaml(ClientModule).decodeFromString<Settings>(settingsFile.readText())
    } catch (e: Exception) {
        val suggested = settingsFile.absoluteFile.parentFile.resolve(settingsFile.nameWithoutExtension + ".suggested.yml")
        println("Settings were incorrect.  Suggested updates are inside ${suggested.absolutePath}.")
        Settings.populateDefaults()
        suggested.writeText(Yaml(ClientModule).encodeToString(Settings))
        e.printStackTrace()
        exitProcess(1)
    }
}

/**
 * A helper function for loading in your settings object from the file.
 * If the file does not exist it will use the result from [makeDefaultSettings] to created one then shutdown the process.
 *
 * @param settingsFile the location of the file to decode [SETTINGS] from.
 * @param makeDefaultSettings a lambda to retrieve a default example of [SETTINGS] to encode to a file.
 */
fun loadSettings(environmentVariablePrefix: String): Settings {
    return Serialization.properties.decodeFromStringMap<Settings>(System.getenv())
}

