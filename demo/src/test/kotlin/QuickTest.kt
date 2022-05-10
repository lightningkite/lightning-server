package com.lightningkite.ktorbatteries.demo

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.publicUrl
import com.lightningkite.ktorbatteries.files.upload
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.settings.loadSettings
import com.lightningkite.ktordb.ServerFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class QuickTest {
    @Test
    fun fileSystemCheck() {
        SetOnce.allowOverwrite {
            val x = loadSettings(File("settings.yaml")) { Settings() }
        }
        runBlocking {
            val uploaded = FilesSettings.instance.root.resolveFile("test.txt").upload(ByteArrayInputStream("Test".toByteArray()))
            println(uploaded.publicUrl)

            val asString = Serialization.json.encodeToString(ServerFile(uploaded.publicUrl))
            println(asString)
            val returned: ServerFile = Serialization.json.decodeFromString(asString)
            println(returned)
        }
    }
}