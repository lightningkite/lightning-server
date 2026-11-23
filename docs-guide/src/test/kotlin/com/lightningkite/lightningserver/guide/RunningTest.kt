package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.guide.samples.HelloServer
import com.lightningkite.lightningserver.settings.IncompleteSettingsException
import com.lightningkite.lightningserver.settings.MissingSettingFile
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.kfile.KFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Verifies the first-run behavior running.md describes, on the engine and server the chapter's serve() uses.
class RunningTest {
    private fun load(file: File) = KtorEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile(file.path), internalSerializersModule)
        settings.ready()
    }

    @Test
    fun `first run writes settings json and throws, second run loads it`() {
        val file = createTempDirectory("running-test").toFile().resolve("settings.json")
        assertFailsWith<MissingSettingFile> { load(file) }
        assertTrue(file.readText().contains("ktorRunConfig"))
        load(file)
    }

    @Test
    fun `a missing required key writes settings suggested json`() {
        val dir = createTempDirectory("running-test").toFile()
        val file = dir.resolve("settings.json")
        file.writeText("{}")
        assertFailsWith<IncompleteSettingsException> { load(file) }
        assertTrue(dir.resolve("settings.suggested.json").exists())
    }
}
