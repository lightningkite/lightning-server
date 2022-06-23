package com.lightningkite.ktorbatteries.settings

import com.charleskorn.kaml.Yaml
import com.lightningkite.ktordb.ClientModule
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.system.exitProcess

/**
 * A helper function for loading in your settings object from the file.
 * If the file does not exist it will use the result from [makeDefaultSettings] to created one then shutdown the process.
 *
 * @param settingsFile the location of the file to decode [SETTINGS] from.
 * @param makeDefaultSettings a lambda to retrieve a default example of [SETTINGS] to encode to a file.
 */
inline fun <reified SETTINGS> loadSettings(settingsFile: File, makeDefaultSettings: () -> SETTINGS): SETTINGS {
    if (!settingsFile.exists()) {
        settingsFile.writeText(
            Yaml().encodeToString(makeDefaultSettings())
        )

        println("Need a settings file - example generated at ${settingsFile.absolutePath}.")
        exitProcess(1)
    }
    try {
        return Yaml(ClientModule).decodeFromString<SETTINGS>(settingsFile.readText())
    } catch (e: Exception) {
        println("Settings were incorrect - see error below.")
        e.printStackTrace()
        exitProcess(1)
    }
}

/**
 * A helper function to start a Ktor server using GeneralServerSettings and the provided Module.
 */
fun runServer(module: Application.() -> Unit) = embeddedServer(
    factory = CIO,
    port = GeneralServerSettings.instance.port,
    host = GeneralServerSettings.instance.host,
    module = {
        try {
            module()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    },
    watchPaths = listOf("classes")
).start(wait = true)