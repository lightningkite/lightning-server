package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.setting
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.metricsSettings
import com.lightningkite.lightningserver.runtime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.*
import java.io.File
import kotlin.test.Test

class SettingsLoaderFileTest {

    @Serializable
    data class Complex(val string: String, val number: Int)

    val testRoot = File("build/test-run")

    object Server: ServerBuilder() {
        val webUrl = setting("webUrl", "http://localhost:8080")
        val processed = setting("processed", "x", getter = { it.repeat(2) })
        val complex = setting("complex", Complex("asdf", 42))
    }
    val allSettings = (Server.settings + listOf<ServerSetting<*, *>>(generalSettings, metricsSettings, com.lightningkite.lightningserver.definition.secretBasis)).toSet()

    @Test
    fun testPropertiesComplete() {
        testRoot.deleteRecursively()
        testRoot.mkdirs()
        val file = testRoot.resolve("test.properties").also {
            it.writeText(
                """
                general.port=8941
                secretBasis=1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
                metrics=none
                webUrl=http://localhost:8080
                processed=x
                complex.string=asdf
                complex.number=42
                """.trimIndent()
            )
        }
        ServerSettings(allSettings)
            .apply { loadFromFile(file, Server.internalSerialization) }
            .allSerializable()
            .forEach { println("${it.key.settingName}: ${it.value}") }
    }
    @Test
    fun testJsonComplete() {
        testRoot.deleteRecursively()
        testRoot.mkdirs()
        val file = testRoot.resolve("test.json").also {
            it.writeText(
                """
                {
                    general: {},
                    metrics: "none",
                    secretBasis: "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                    webUrl: "http://localhost:8080",
                    processed: "x",
                    complex: {
                    string: "asdf",
                    number: 42
                    }
                }
                """.trimIndent()
            )
        }
        ServerSettings(allSettings)
            .apply { loadFromFile(file, Server.internalSerialization) }
            .allSerializable()
            .forEach { println("${it.key.settingName}: ${it.value}") }
    }
    @Test
    fun testPropertiesClean() {
        testRoot.deleteRecursively()
        testRoot.mkdirs()
        val file = testRoot.resolve("test.properties").also {
            it.writeText("")
        }
        try {
            ServerSettings(allSettings)
                .apply { loadFromFile(file, Server.internalSerialization) }
                .allSerializable()
                .forEach { println("${it.key.settingName}: ${it.value}") }
        } catch(e: IncompleteSettingsException) {
            println("---SUGGESTED---\n${e.suggestedFile.readText()}\n")
            e.suggestedFile.copyRecursively(file, overwrite = true)
        }
        ServerSettings(allSettings)
            .apply { loadFromFile(file, Server.internalSerialization) }
            .allSerializable()
            .forEach { println("${it.key.settingName}: ${it.value}") }
    }
    @Test
    fun testJsonClean() {
        testRoot.deleteRecursively()
        testRoot.mkdirs()
        val file = testRoot.resolve("test.json").also {
            it.writeText("{}")
        }
        try {
            ServerSettings(allSettings)
                .apply { loadFromFile(file, Server.internalSerialization) }
                .allSerializable()
                .forEach { println("${it.key.settingName}: ${it.value}") }
        } catch(e: IncompleteSettingsException) {
            println("---SUGGESTED---\n${e.suggestedFile.readText()}\n")
            e.suggestedFile.copyRecursively(file, overwrite = true)
        }
        ServerSettings(allSettings)
            .apply { loadFromFile(file, Server.internalSerialization) }
            .allSerializable()
            .forEach { println("${it.key.settingName}: ${it.value}") }
    }
}