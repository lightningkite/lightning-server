package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.services.kfile.workingDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsLoaderFileTest {

    @Serializable
    data class Complex(val string: String, val number: Int)

    val testRoot = workingDirectory.then("build", "test-run")

    object Server : ServerBuilder() {
        val webUrl = setting("webUrl", "http://localhost:8080")
        val processed = setting("processed", "x", getter = { it.repeat(2) })
        val complex = setting("complex", Complex("asdf", 42))
    }

    val allSettings = (Server.build().settings + listOf<ServerSetting<*, *>>(
        generalSettings,
        com.lightningkite.lightningserver.definition.secretBasis,
    )).toSet()

    @Test
    fun testPropertiesComplete() {
        testRoot.deleteRecursively()
        testRoot.createDirectories()
        val file = testRoot.resolve("test.properties").also {
            it.writeString(
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
            .apply { loadFromFile(file, EmptySerializersModule()) }
            .allSerializable()
            .forEach { println("${it.key.name}: ${it.value}") }
    }

    @Serializable
    data class HashHolder(val color: String, val url: String, val plain: String)

    /**
     * Regression test: a '#' inside a property value (hex color, URL fragment) must be preserved.
     * The parser previously treated '#' as a comment marker anywhere on the line, silently
     * truncating such values.
     */
    @Test
    fun testPropertiesValueContainingHashIsPreserved() {
        val format = settingsFormat("properties", EmptySerializersModule())
        val parsed = format.decodeFromString(
            HashHolder.serializer(),
            """
            # this whole line is a comment and must be ignored
            color=#FF0000
            url=https://example.com/page#section
            plain=value
            """.trimIndent()
        )
        assertEquals("#FF0000", parsed.color)
        assertEquals("https://example.com/page#section", parsed.url)
        assertEquals("value", parsed.plain)
    }

    @Test
    fun testJsonComplete() {
        testRoot.deleteRecursively()
        testRoot.createDirectories()
        val file = testRoot.resolve("test.json").also {
            it.writeString(
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
            .apply { loadFromFile(file, EmptySerializersModule()) }
            .allSerializable()
            .forEach { println("${it.key.name}: ${it.value}") }
    }

    @Test
    fun testPropertiesClean() {
        testRoot.deleteRecursively()
        testRoot.createDirectories()
        val file = testRoot.then("test.properties").also {
            it.writeString("")
        }
        try {
            ServerSettings(allSettings)
                .apply { loadFromFile(file, EmptySerializersModule()) }
                .allSerializable()
                .forEach { println("${it.key.name}: ${it.value}") }
        } catch (e: IncompleteSettingsException) {
            println("---SUGGESTED---\n${e.suggestedFile?.readString()}\n")
            e.suggestedFile?.copyTo(file, overwrite = true)
        }
        ServerSettings(allSettings)
            .apply { loadFromFile(file, EmptySerializersModule()) }
            .allSerializable()
            .forEach { println("${it.key.name}: ${it.value}") }
    }

    @Test
    fun testJsonClean() {
        testRoot.deleteRecursively()
        testRoot.createDirectories()
        val file = testRoot.then("test.json").also {
            it.writeString("{}")
        }
        try {
            ServerSettings(allSettings)
                .apply { loadFromFile(file, EmptySerializersModule()) }
                .allSerializable()
                .forEach { println("${it.key.name}: ${it.value}") }
        } catch (e: IncompleteSettingsException) {
            println("---SUGGESTED---\n${e.suggestedFile?.readString()}\n")
            e.suggestedFile?.copyTo(file, overwrite = true)
        }
        ServerSettings(allSettings)
            .apply { loadFromFile(file, EmptySerializersModule()) }
            .allSerializable()
            .forEach { println("${it.key.name}: ${it.value}") }
    }
}