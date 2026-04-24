// by Claude
package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.kfile.KFile
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.test.*

/**
 * Tests for settings-related exceptions.
 */
class SettingsExceptionsTest {

    // Helper to create a test KFile using actual temp files
    private fun createTempKFile(name: String): KFile {
        val tempFile = File.createTempFile(name.substringBefore("."), name.substringAfter(".", ""))
        tempFile.deleteOnExit()
        return KFile(SystemFileSystem, Path(tempFile.absolutePath))
    }

    // Helper to create a test setting
    private fun testSetting(name: String): ServerSetting.Direct<String> = ServerSetting(
        name = name,
        default = "default",
        serializer = String.serializer(),
        instructions = "Test instructions",
        optional = false
    )

    @Test
    fun `IncompleteSettingsException contains missing settings`() {
        val setting1 = testSetting("test1")
        val setting2 = testSetting("test2")
        val missing = setOf<ServerSetting<*, *>>(setting1, setting2)
        val suggestedFile = createTempKFile("test-settings.json")

        val exception = IncompleteSettingsException(missing, suggestedFile)

        assertEquals(missing, exception.missing)
        assertEquals(suggestedFile, exception.suggestedFile)
    }

    @Test
    fun `IncompleteSettingsException message contains setting names`() {
        val setting1 = testSetting("database")
        val missing = setOf<ServerSetting<*, *>>(setting1)
        val suggestedFile = createTempKFile("settings.json")

        val exception = IncompleteSettingsException(missing, suggestedFile)

        assertTrue(exception.message!!.contains("database"))
        assertTrue(exception.message!!.contains("suggested settings"))
    }

    @Test
    fun `IncompleteSettingsException with multiple missing keys`() {
        val setting1 = testSetting("key1")
        val setting2 = testSetting("key2")
        val setting3 = testSetting("key3")
        val missing = setOf<ServerSetting<*, *>>(setting1, setting2, setting3)
        val suggestedFile = createTempKFile("settings.json")

        val exception = IncompleteSettingsException(missing, suggestedFile)

        assertTrue(exception.message!!.contains("key1"))
        assertTrue(exception.message!!.contains("key2"))
        assertTrue(exception.message!!.contains("key3"))
    }

    @Test
    fun `MissingSettingFile exception message`() {
        val suggestedFile = createTempKFile("created-settings.json")

        val exception = MissingSettingFile(suggestedFile)

        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("Settings file does not exists"))
        assertTrue(exception.message!!.contains("Created file"))
    }
}
