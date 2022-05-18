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