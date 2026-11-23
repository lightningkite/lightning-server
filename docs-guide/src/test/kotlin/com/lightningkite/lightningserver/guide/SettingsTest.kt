package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.EmptySerializersModule
import java.nio.file.Files
import kotlin.test.*

// Checks the file-loading behavior settings.md describes.
class SettingsTest {
    private val dir = Files.createTempDirectory("settings-test").toFile()
    private val file = KFile(dir.resolve("settings.json").path)
    private fun load(target: KFile = file) =
        ServerSettings(StoreSettingsServer.build()).also { it.loadFromFile(target, EmptySerializersModule()) }

    @Test fun `settings sample overrides values in tests`() { shopSettingsTest() }

    @Test fun `first run writes every key and throws`() {
        assertFailsWith<MissingSettingFile> { load() }
        val keys = Json.parseToJsonElement(file.readString()).jsonObject.keys
        assertTrue(keys.containsAll(listOf("supportEmail", "featureFlags", "maintenanceMessage", "uploadLimitMegabytes")))
        load()
    }

    @Test fun `missing required keys write a suggested file`() {
        file.writeString("""{ "supportEmail": "help@shop.test", "typo": 1 }""")
        val e = assertFailsWith<IncompleteSettingsException> { load() }
        assertEquals(setOf("featureFlags", "uploadLimitMegabytes"), e.missing.map { it.name }.toSet())
        assertTrue(dir.resolve("settings.suggested.json").exists())
    }

    @Test fun `defaults file is overridden by the main file`() {
        dir.resolve("base.json").writeText("""{ "supportEmail": "base@shop.test", "uploadLimitMegabytes": 5 }""")
        file.writeString("""{ "defaults": "base.json", "uploadLimitMegabytes": 20, "featureFlags": {} }""")
        val values = load().allSerializable().mapKeys { it.key.name }
        assertEquals("base@shop.test", values["supportEmail"])
        assertEquals(20, values["uploadLimitMegabytes"])
    }

    @Test fun `properties files use dotted keys and full-line comments`() {
        val props = KFile(dir.resolve("settings.properties").path)
        props.writeString(
            """
            # comment
            supportEmail=help@shop.test
            featureFlags.newCheckout=true
            featureFlags.maxCartItems=10
            uploadLimitMegabytes=3
            """.trimIndent()
        )
        val values = load(props).allSerializable().mapKeys { it.key.name }
        assertEquals(FeatureFlags(newCheckout = true, maxCartItems = 10), values["featureFlags"])
    }

    @Test fun `bound settings are left out of the file`() {
        val settings = ServerSettings(SharedCacheServer.build()) + engineCache
        assertFailsWith<MissingSettingFile> { settings.loadFromFile(file, EmptySerializersModule()) }
        val keys = Json.parseToJsonElement(file.readString()).jsonObject.keys
        assertTrue("cache" in keys)
        assertFalse("engine-cache" in keys)
    }
}
