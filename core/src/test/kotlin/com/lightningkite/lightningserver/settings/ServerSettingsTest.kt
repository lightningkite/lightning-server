package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ServerSettings lifecycle and state management.
 *
 * Note: Tests requiring ServerRuntime context are limited since that requires
 * a full server setup. These tests focus on the configuration phase and
 * lifecycle management that can be tested independently.
 */
class ServerSettingsTest {

    @Serializable
    data class TestConfig(val value: String)

    object TestServer : ServerBuilder() {
        val requiredSetting = setting("required", "default", optional = false)
        val optionalSetting = setting("optional", "default", optional = true)
        val transformedSetting = setting("transformed", "x", getter = { it.repeat(3) })

        val a = setting("A", "A")
        val b = setting("B", "B")

        init {
            b bind a
        }
    }

    @Test
    fun testInitiallyNotReady() {
        val settings = ServerSettings(TestServer.build().settings.toSet())
        assertFalse(settings.ready)
    }

    @Test
    fun testCanSetBeforeReady() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        with(settings) {
            TestServer.requiredSetting set "test-value"
            TestServer.optionalSetting set "optional-value"
            TestServer.transformedSetting set "y"
        }

        // No exception should be thrown
    }

    @Test
    fun testCannotSetAfterReadyUsingDefaults() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        with(settings) {
            TestServer.requiredSetting set "test"
        }

        settings.readyUsingDefaults()
        assertTrue(settings.ready)

        // Cannot set after ready
        assertFailsWith<IllegalStateException> {
            with(settings) {
                TestServer.requiredSetting set "new-value"
            }
        }
    }

    @Test
    fun testCannotIncludeAfterReady() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        settings.readyUsingDefaults()

        assertFailsWith<IllegalStateException> {
            settings.include(mapOf(TestServer.requiredSetting to "value"))
        }
    }

    @Test
    fun testUseDefault() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        with(settings) {
            // Explicitly use default (though it's the default anyway)
            TestServer.requiredSetting.useDefault()
        }

        val serializable = settings.allSerializable()
        assertEquals("default", serializable[TestServer.requiredSetting])
    }

    @Test
    fun testAllSerializableWithDefaults() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        with(settings) {
            TestServer.requiredSetting set "test-value"
        }

        val serializable = settings.allSerializable()

        // Should include set values and defaults
        assertEquals("test-value", serializable[TestServer.requiredSetting])
        assertEquals("default", serializable[TestServer.optionalSetting]) // Uses default
        assertEquals("x", serializable[TestServer.transformedSetting]) // Serializable form, not transformed
    }

    @Test
    fun testAllSerializableIncludesAllSettings() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        val serializable = settings.allSerializable()

        // Should have entries for all settings
        assertTrue(TestServer.requiredSetting in serializable)
        assertTrue(TestServer.optionalSetting in serializable)
        assertTrue(TestServer.transformedSetting in serializable)
    }

    @Test
    fun testIncludeMap() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        val valuesToInclude: Map<com.lightningkite.lightningserver.definition.ServerSetting<*, *>, Any?> = mapOf(
            TestServer.requiredSetting to "from-map",
            TestServer.optionalSetting to "also-from-map"
        )

        settings.include(valuesToInclude)

        val serializable = settings.allSerializable()
        assertEquals("from-map", serializable[TestServer.requiredSetting])
        assertEquals("also-from-map", serializable[TestServer.optionalSetting])
    }

    @Test
    fun testReadyUsingDefaults() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        // Don't set anything

        // readyUsingDefaults bypasses validation
        settings.readyUsingDefaults()

        assertTrue(settings.ready)
    }

    @Test
    fun testCannotSetSameSettingTwice() {
        val settings = ServerSettings(TestServer.build().settings.toSet())

        with(settings) {
            TestServer.requiredSetting set "first"

            // Cannot set the same setting twice
            assertFailsWith<com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError> {
                TestServer.requiredSetting set "second"
            }
        }
    }

    @Test
    fun testSettingOverrides() {
        TestServer.test({}) {
            println("Got ${serverRuntime.settings.overrides.size} overrides")
            assertEquals("A", a())
            assertEquals("A", b()) // should defer to 'a' as configured in the server
        }
    }
}
