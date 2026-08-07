package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.kfile.workingDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.After
import org.junit.Test
import kotlin.test.*

/**
 * Tests for advanced settings features including chained defaults,
 * circular dependency detection, and properties file improvements.
 */
class SettingsAdvancedFeaturesTest {

    @Serializable
    data class Config(val host: String, val port: Int)

    object TestServer : ServerBuilder() {
        val setting1 = setting("setting1", "default1")
        val setting2 = setting("setting2", "default2")
        val config = setting("config", Config("localhost", 8080))
    }

    private val testRoot = workingDirectory.then("build", "test-advanced")

    @After
    fun cleanup() {
        testRoot.deleteRecursively()
    }

    @Test
    fun testPropertiesWithComments() {
        testRoot.createDirectories()
        val file = testRoot.then("test.properties").also {
            it.writeString(
                """
                # This is a comment
                setting1=value1
                # Another comment
                setting2=value2

                # Empty lines above should be ignored
                config.host=example.com
                config.port=9090
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(file, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("value1", serializable[TestServer.setting1])
        assertEquals("value2", serializable[TestServer.setting2])
        assertEquals(Config("example.com", 9090), serializable[TestServer.config])
    }

    @Test
    fun testPropertiesWithInlineComments() {
        testRoot.createDirectories()
        val file = testRoot.then("test.properties").also {
            it.writeString(
                """
                setting1=value1 # inline comment
                setting2=value2# no space before comment
                config.host=example.com
                config.port=9090
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(file, EmptySerializersModule())

        val serializable = settings.allSerializable()
        // Standard .properties format does NOT support inline comments — '#' only acts as a
        // comment marker when it is the first non-blank character on the line. A '#' inside a
        // value (hex color, URL fragment, or text containing '#') must be preserved verbatim.
        assertEquals("value1 # inline comment", serializable[TestServer.setting1])
        assertEquals("value2# no space before comment", serializable[TestServer.setting2])
    }

    @Test
    fun testPropertiesWithEmptyLines() {
        testRoot.createDirectories()
        val file = testRoot.then("test.properties").also {
            it.writeString(
                """
                setting1=value1


                setting2=value2

                config.host=example.com
                config.port=9090
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(file, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("value1", serializable[TestServer.setting1])
        assertEquals("value2", serializable[TestServer.setting2])
    }

    @Test
    fun testDefaultsChainJson() {
        testRoot.createDirectories()

        // Create base config
        val baseFile = testRoot.then("base.json").also {
            it.writeString(
                """
                {
                  "setting1": "from-base",
                  "setting2": "also-from-base",
                  "config": {
                    "host": "base-host",
                    "port": 1111
                  }
                }
                """.trimIndent()
            )
        }

        // Create main config that references base
        val mainFile = testRoot.then("main.json").also {
            it.writeString(
                """
                {
                  "defaults": "base.json",
                  "setting2": "overridden-in-main",
                  "config": {
                    "host": "main-host",
                    "port": 2222
                  }
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(mainFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        // setting1 comes from base
        assertEquals("from-base", serializable[TestServer.setting1])
        // setting2 is overridden in main
        assertEquals("overridden-in-main", serializable[TestServer.setting2])
        // config is fully overridden in main
        assertEquals(Config("main-host", 2222), serializable[TestServer.config])
    }

    @Test
    fun testDefaultsChainProperties() {
        testRoot.createDirectories()

        // Create base config as properties
        val baseFile = testRoot.then("base.properties").also {
            it.writeString(
                """
                setting1=from-base
                setting2=also-from-base
                config.host=base-host
                config.port=1111
                """.trimIndent()
            )
        }

        // Create main config that references base
        val mainFile = testRoot.then("main.json").also {
            it.writeString(
                """
                {
                  "defaults": "base.properties",
                  "setting2": "overridden-in-main",
                  "config": {
                    "host": "main-host",
                    "port": 2222
                  }
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(mainFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("from-base", serializable[TestServer.setting1])
        assertEquals("overridden-in-main", serializable[TestServer.setting2])
        assertEquals(Config("main-host", 2222), serializable[TestServer.config])
    }

    @Test
    fun testDefaultsChainMultipleLevels() {
        testRoot.createDirectories()

        // Level 1: Base
        testRoot.then("level1.json").writeString(
            """
            {
              "setting1": "level1"
            }
            """.trimIndent()
        )

        // Level 2: References level1
        testRoot.then("level2.json").writeString(
            """
            {
              "defaults": "level1.json",
              "setting2": "level2"
            }
            """.trimIndent()
        )

        // Level 3: References level2
        val mainFile = testRoot.then("level3.json").also {
            it.writeString(
                """
                {
                  "defaults": "level2.json",
                  "config": {
                    "host": "level3",
                    "port": 3333
                  }
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(mainFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("level1", serializable[TestServer.setting1])
        assertEquals("level2", serializable[TestServer.setting2])
        assertEquals(Config("level3", 3333), serializable[TestServer.config])
    }

    @Test
    fun testCircularDependencyDetected() {
        testRoot.createDirectories()

        // File A references B
        testRoot.then("fileA.json").writeString(
            """
            {
              "defaults": "fileB.json",
              "setting1": "from-A"
            }
            """.trimIndent()
        )

        // File B references A (creates cycle)
        val fileB = testRoot.then("fileB.json").also {
            it.writeString(
                """
                {
                  "defaults": "fileA.json",
                  "setting2": "from-B"
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())

        val exception = assertFailsWith<SerializationException> {
            settings.loadFromFile(fileB, EmptySerializersModule())
        }

        assertTrue(exception.message!!.contains("Circular defaults chain detected"))
    }

    @Test
    fun testSelfReferenceDetected() {
        testRoot.createDirectories()

        // File references itself
        val file = testRoot.then("self.json").also {
            it.writeString(
                """
                {
                  "defaults": "self.json",
                  "setting1": "value"
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())

        val exception = assertFailsWith<SerializationException> {
            settings.loadFromFile(file, EmptySerializersModule())
        }

        assertTrue(exception.message!!.contains("Circular defaults chain detected"))
    }

    @Test
    fun testMissingDefaultsFileError() {
        testRoot.createDirectories()

        val file = testRoot.then("main.json").also {
            it.writeString(
                """
                {
                  "defaults": "nonexistent.json",
                  "setting1": "value"
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())

        val exception = assertFailsWith<SerializationException> {
            settings.loadFromFile(file, EmptySerializersModule())
        }

        assertTrue(exception.message!!.contains("does not exist"))
        assertTrue(exception.message!!.contains("nonexistent.json"))
    }

    @Test
    fun testRelativeDefaultsPaths() {
        testRoot.createDirectories()

        // Create subdirectory
        val subdir = testRoot.then("configs")
        subdir.createDirectories()

        // Create base in subdirectory
        subdir.then("base.json").writeString(
            """
            {
              "setting1": "from-base"
            }
            """.trimIndent()
        )

        // Create main that references base with relative path
        val mainFile = subdir.then("main.json").also {
            it.writeString(
                """
                {
                  "defaults": "base.json",
                  "setting2": "from-main",
                  "config": {
                    "host": "localhost",
                    "port": 8080
                  }
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(mainFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("from-base", serializable[TestServer.setting1])
        assertEquals("from-main", serializable[TestServer.setting2])
    }

    @Test
    fun testTildeExpansionInDefaults() {
        testRoot.createDirectories()

        // Create a file in the test directory that we'll reference with a path
        // (We can't actually test ~ expansion to home without writing to home directory,
        // but we can test that tilde gets replaced)
        val file = testRoot.then("main.json").also {
            it.writeString(
                """
                {
                  "defaults": "~/should-fail.json",
                  "setting1": "value"
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())

        // Should fail because file doesn't exist, but the error should show
        // the expanded path (not containing ~)
        val exception = assertFailsWith<SerializationException> {
            settings.loadFromFile(file, EmptySerializersModule())
        }

        // The error message should contain the home directory path, not ~
        assertTrue(!exception.message!!.contains("~"))
        assertTrue(exception.message!!.contains("should-fail.json"))
    }

    @Test
    fun testPropertiesFormatExtensionDetection() {
        testRoot.createDirectories()

        // Test that .properties extension is detected
        val propsFile = testRoot.then("test.properties").also {
            it.writeString(
                """
                setting1=props-value
                setting2=props-value2
                config.host=localhost
                config.port=8080
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(propsFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("props-value", serializable[TestServer.setting1])
    }

    @Test
    fun testJsonFormatForOtherExtensions() {
        testRoot.createDirectories()

        // Test that other extensions use JSON format
        val configFile = testRoot.then("test.config").also {
            it.writeString(
                """
                {
                  "setting1": "json-value",
                  "setting2": "json-value2",
                  "config": {
                    "host": "localhost",
                    "port": 8080
                  }
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(configFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        assertEquals("json-value", serializable[TestServer.setting1])
    }

    @Test
    fun testDefaultsOverridesPriority() {
        testRoot.createDirectories()

        // Base defines all three settings
        testRoot.then("base.json").writeString(
            """
            {
              "setting1": "base-1",
              "setting2": "base-2",
              "config": {
                "host": "base-host",
                "port": 1111
              }
            }
            """.trimIndent()
        )

        // Main only overrides one
        val mainFile = testRoot.then("main.json").also {
            it.writeString(
                """
                {
                  "defaults": "base.json",
                  "setting1": "main-1"
                }
                """.trimIndent()
            )
        }

        val settings = ServerSettings(TestServer.build().settings.toSet())
        settings.loadFromFile(mainFile, EmptySerializersModule())

        val serializable = settings.allSerializable()
        // Main overrides setting1
        assertEquals("main-1", serializable[TestServer.setting1])
        // Base values are used for others
        assertEquals("base-2", serializable[TestServer.setting2])
        assertEquals(Config("base-host", 1111), serializable[TestServer.config])
    }
}
