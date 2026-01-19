// Tests created by Claude
package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for SettingsSerializer which handles dynamic serialization/deserialization
 * of server settings including the defaults file feature.
 */
@OptIn(InternalLightningServerApi::class)
class SettingsSerializerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Helper settings for testing
    private val stringSetting = ServerSetting("stringValue", "default-string", String.serializer())
    private val intSetting = ServerSetting("intValue", 42, Int.serializer())
    private val optionalSetting = ServerSetting("optionalValue", "optional-default", String.serializer(), optional = true)
    private val boolSetting = ServerSetting("boolValue", false, Boolean.serializer())

    @Test
    fun `serialize single setting`() {
        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val map = mapOf<ServerSetting<*, *>, Any?>(stringSetting to "test-value")
        val serialized = json.encodeToString(serializer, map)

        assertTrue(serialized.contains("\"stringValue\""))
        assertTrue(serialized.contains("\"test-value\""))
    }

    @Test
    fun `serialize multiple settings`() {
        val settings = listOf(stringSetting, intSetting, boolSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val map = mapOf<ServerSetting<*, *>, Any?>(
            stringSetting to "hello",
            intSetting to 100,
            boolSetting to true
        )
        val serialized = json.encodeToString(serializer, map)

        assertTrue(serialized.contains("\"stringValue\":\"hello\"") || serialized.contains("\"stringValue\": \"hello\""))
        assertTrue(serialized.contains("\"intValue\":100") || serialized.contains("\"intValue\": 100"))
        assertTrue(serialized.contains("\"boolValue\":true") || serialized.contains("\"boolValue\": true"))
    }

    @Test
    fun `serialize only includes settings in map`() {
        val settings = listOf(stringSetting, intSetting, boolSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        // Only include stringSetting in the map
        val map = mapOf<ServerSetting<*, *>, Any?>(stringSetting to "partial")
        val serialized = json.encodeToString(serializer, map)

        assertTrue(serialized.contains("\"stringValue\""))
        // intValue and boolValue should not be present since they weren't in the map
        assertTrue(!serialized.contains("\"intValue\""))
        assertTrue(!serialized.contains("\"boolValue\""))
    }

    @Test
    fun `deserialize single setting`() {
        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val jsonInput = """{"stringValue":"deserialized-value"}"""
        val result = json.decodeFromString(serializer, jsonInput)

        assertEquals("deserialized-value", result[stringSetting])
    }

    @Test
    fun `deserialize multiple settings`() {
        val settings = listOf(stringSetting, intSetting, boolSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val jsonInput = """{"stringValue":"str","intValue":99,"boolValue":true}"""
        val result = json.decodeFromString(serializer, jsonInput)

        assertEquals("str", result[stringSetting])
        assertEquals(99, result[intSetting])
        assertEquals(true, result[boolSetting])
    }

    @Test
    fun `deserialize ignores unknown fields`() {
        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val jsonInput = """{"stringValue":"valid","unknownField":"ignored"}"""
        val result = json.decodeFromString(serializer, jsonInput)

        assertEquals("valid", result[stringSetting])
        assertEquals(1, result.size) // Only the known setting should be in the result
    }

    @Test
    fun `deserialize with missing optional setting`() {
        val settings = listOf(stringSetting, optionalSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        // Only stringSetting is provided, optionalSetting is missing
        val jsonInput = """{"stringValue":"provided"}"""
        val result = json.decodeFromString(serializer, jsonInput)

        assertEquals("provided", result[stringSetting])
        // optionalSetting should not be in the result (it's optional and wasn't provided)
        assertTrue(optionalSetting !in result)
    }

    @Test
    fun `roundtrip serialization preserves values`() {
        val settings = listOf(stringSetting, intSetting, boolSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val originalMap = mapOf<ServerSetting<*, *>, Any?>(
            stringSetting to "roundtrip-test",
            intSetting to 12345,
            boolSetting to true
        )

        val serialized = json.encodeToString(serializer, originalMap)
        val deserialized = json.decodeFromString(serializer, serialized)

        assertEquals(originalMap[stringSetting], deserialized[stringSetting])
        assertEquals(originalMap[intSetting], deserialized[intSetting])
        assertEquals(originalMap[boolSetting], deserialized[boolSetting])
    }

    @Test
    fun `descriptor has element for each setting plus defaults`() {
        val settings = listOf(stringSetting, intSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val descriptor = serializer.descriptor

        // Should have elements for each setting plus the "defaults" element
        assertEquals(3, descriptor.elementsCount) // 2 settings + 1 defaults

        // Verify element names
        assertEquals("stringValue", descriptor.getElementName(0))
        assertEquals("intValue", descriptor.getElementName(1))
        assertEquals("defaults", descriptor.getElementName(2))
    }

    @Test
    fun `defaults file loads settings from another file`() {
        // Create the defaults file
        val defaultsFile = tempFolder.newFile("defaults.json")
        defaultsFile.writeText("""{"stringValue":"from-defaults","intValue":999}""")

        // Create the main settings file that references defaults
        val mainFile = tempFolder.newFile("main.json")
        mainFile.writeText("""{"defaults":"defaults.json","boolValue":true}""")

        val settings = listOf(stringSetting, intSetting, boolSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = tempFolder.root)

        val result = json.decodeFromString(serializer, mainFile.readText())

        // Should have values from both files, with main file taking priority
        assertEquals("from-defaults", result[stringSetting])
        assertEquals(999, result[intSetting])
        assertEquals(true, result[boolSetting])
    }

    @Test
    fun `main file overrides defaults file`() {
        // Create the defaults file
        val defaultsFile = tempFolder.newFile("defaults.json")
        defaultsFile.writeText("""{"stringValue":"default-value","intValue":100}""")

        // Create main file that overrides stringValue
        val mainFile = tempFolder.newFile("main.json")
        mainFile.writeText("""{"defaults":"defaults.json","stringValue":"overridden"}""")

        val settings = listOf(stringSetting, intSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = tempFolder.root)

        val result = json.decodeFromString(serializer, mainFile.readText())

        // stringValue should be overridden, intValue should come from defaults
        assertEquals("overridden", result[stringSetting])
        assertEquals(100, result[intSetting])
    }

    // Test for tilde expansion removed - cannot reliably test without writing to home directory
    // which is blocked in sandboxed environments

    @Test
    fun `defaults disabled when relativeTo is null`() {
        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val jsonInput = """{"defaults":"some-file.json"}"""

        val exception = assertFailsWith<SerializationException> {
            json.decodeFromString(serializer, jsonInput)
        }

        assertTrue(exception.message?.contains("Defaults file usage is disabled") == true)
    }

    @Test
    fun `circular defaults chain detected`() {
        // Create two files that reference each other
        val fileA = tempFolder.newFile("a.json")
        val fileB = tempFolder.newFile("b.json")

        fileA.writeText("""{"defaults":"b.json"}""")
        fileB.writeText("""{"defaults":"a.json"}""")

        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = tempFolder.root)

        val exception = assertFailsWith<SerializationException> {
            json.decodeFromString(serializer, fileA.readText())
        }

        assertTrue(exception.message?.contains("Circular defaults chain detected") == true)
    }

    @Test
    fun `self-referencing defaults chain detected`() {
        val selfRef = tempFolder.newFile("self.json")
        selfRef.writeText("""{"defaults":"self.json"}""")

        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = tempFolder.root)

        val exception = assertFailsWith<SerializationException> {
            json.decodeFromString(serializer, selfRef.readText())
        }

        assertTrue(exception.message?.contains("Circular defaults chain detected") == true)
    }

    @Test
    fun `nonexistent defaults file throws`() {
        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = tempFolder.root)

        val jsonInput = """{"defaults":"nonexistent.json"}"""

        val exception = assertFailsWith<SerializationException> {
            json.decodeFromString(serializer, jsonInput)
        }

        assertTrue(exception.message?.contains("does not exist") == true)
    }

    @Test
    fun `nested defaults chain works`() {
        // Create a chain: main -> level1 -> level2
        val level2 = tempFolder.newFile("level2.json")
        level2.writeText("""{"stringValue":"from-level2"}""")

        val level1 = tempFolder.newFile("level1.json")
        level1.writeText("""{"defaults":"level2.json","intValue":111}""")

        val main = tempFolder.newFile("main.json")
        main.writeText("""{"defaults":"level1.json","boolValue":true}""")

        val settings = listOf(stringSetting, intSetting, boolSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = tempFolder.root)

        val result = json.decodeFromString(serializer, main.readText())

        assertEquals("from-level2", result[stringSetting])
        assertEquals(111, result[intSetting])
        assertEquals(true, result[boolSetting])
    }

    @Test
    fun `deserialize empty object`() {
        val settings = listOf(stringSetting, optionalSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val jsonInput = """{}"""
        val result = json.decodeFromString(serializer, jsonInput)

        // Result should be empty since no values were provided
        assertTrue(result.isEmpty())
    }

    @Test
    fun `serialize empty map produces minimal json`() {
        val settings = listOf(stringSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val emptyMap = emptyMap<ServerSetting<*, *>, Any?>()
        val serialized = json.encodeToString(serializer, emptyMap)

        assertEquals("{}", serialized)
    }

    @Test
    fun `serialize with null value`() {
        // Some settings might have nullable types
        val nullableSetting = ServerSetting("nullableValue", null as String?, String.serializer().nullable)
        val settings = listOf(nullableSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val map = mapOf<ServerSetting<*, *>, Any?>(nullableSetting to null)
        val serialized = json.encodeToString(serializer, map)

        assertTrue(serialized.contains("null"))
    }

    @Test
    fun `deserialize with null value`() {
        val nullableSetting = ServerSetting("nullableValue", null as String?, String.serializer().nullable)
        val settings = listOf(nullableSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = null)

        val jsonInput = """{"nullableValue":null}"""
        val result = json.decodeFromString(serializer, jsonInput)

        assertTrue(nullableSetting in result)
        assertEquals(null, result[nullableSetting])
    }

    @Test
    fun `descriptor unique hash based on settings`() {
        val settings1 = listOf(stringSetting)
        val settings2 = listOf(intSetting)

        val serializer1 = SettingsSerializer(settings1, SerializersModule { }, relativeTo = null)
        val serializer2 = SettingsSerializer(settings2, SerializersModule { }, relativeTo = null)

        // Descriptor names should be different due to different settings
        assertTrue(serializer1.descriptor.serialName != serializer2.descriptor.serialName)
    }

    @Test
    fun `relative path resolution for defaults`() {
        // Create a subdirectory with its own defaults file
        val subdir = tempFolder.newFolder("config")
        val subdirDefaults = File(subdir, "shared.json")
        subdirDefaults.writeText("""{"stringValue":"from-subdir"}""")

        // Main file in subdir references local file
        val subdirMain = File(subdir, "main.json")
        subdirMain.writeText("""{"defaults":"shared.json","intValue":50}""")

        val settings = listOf(stringSetting, intSetting)
        val serializer = SettingsSerializer(settings, SerializersModule { }, relativeTo = subdir)

        val result = json.decodeFromString(serializer, subdirMain.readText())

        assertEquals("from-subdir", result[stringSetting])
        assertEquals(50, result[intSetting])
    }
}
