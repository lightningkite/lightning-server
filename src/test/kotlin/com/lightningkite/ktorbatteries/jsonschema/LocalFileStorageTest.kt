package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.publicUrl
import com.lightningkite.ktorbatteries.files.upload
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import kotlinx.coroutines.runBlocking
import org.apache.commons.vfs2.VFS
import org.junit.Test
import kotlin.test.assertEquals


class LocalFileStorageTest {

    @Test
    fun testPublicUrl() {

        val testText = """asdf54298344654sadf6519843w4568423U*()&^*(&^%UYGJF)(*&^&^%$@"""
        val testPath = "/testItem.txt"
        val testFile = runBlocking {
            VFS.getManager().upload(testText.byteInputStream(), "${FilesSettings.instance.storageUrl}$testPath")
        }
        assertEquals("${GeneralServerSettings.instance.publicUrl}$testPath", testFile.publicUrl)
    }

    // These other tests don't work because they step on each others toes when mass running tests.
// .
//    @Test
//    fun testNonPrefix(){
//        settingsFile = File("./src/test/kotlin/com/emergent3/TestSettings.yaml")
//        val newLocation = testFolder.removePrefix("file:///")
//        settingsFile.writeText(
//            Yaml(emergenThreeJson.serializersModule).encodeToString(settings.copy(fileStorageLocation = newLocation))
//        )
//        val testText = """asdf54298344654sadf6519843w4568423U*()&^*(&^%UYGJF)(*&^&^%$@"""
//        val testPath = "/testItem.txt"
//        val testFile = runBlocking { VFS.getManager().upload(testText.byteInputStream(), "$newLocation$testPath") }
//        assertEquals("${Settings.hostConfig.serverUrl}$testPath", testFile.publicUrl)
//        settingsFile.delete()
//        File(newLocation).deleteRecursively()
//    }
//
//    @Test
//    fun testCase(){
//        settingsFile = File("./src/test/kotlin/com/emergent3/TestSettings.yaml")
//        val newLocation = testFolder.substringBeforeLast("/") + "/TESTMEDIA"
//        settingsFile.writeText(
//            Yaml(emergenThreeJson.serializersModule).encodeToString(settings.copy(fileStorageLocation = newLocation))
//        )
//        val testText = """asdf54298344654sadf6519843w4568423U*()&^*(&^%UYGJF)(*&^&^%$@"""
//        val testPath = "/testItem.txt"
//        val testFile = runBlocking { VFS.getManager().upload(testText.byteInputStream(), "$newLocation$testPath") }
//        assertEquals("${Settings.hostConfig.serverUrl}$testPath", testFile.publicUrl)
//        settingsFile.delete()
//        File(newLocation.removePrefix("file:///")).deleteRecursively()
//    }

}