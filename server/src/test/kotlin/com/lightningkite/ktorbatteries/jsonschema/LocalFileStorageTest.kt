package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.publicUrl
import com.lightningkite.ktorbatteries.files.upload
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import kotlinx.coroutines.runBlocking
import org.apache.commons.vfs2.VFS
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals


class LocalFileStorageTest {

//    @Test
//    fun testPublicUrl() {
//        SetOnce.allowOverwrite {
//            GeneralServerSettings()
//            FilesSettings.File("local/azureurl.txt").readText()
//        }
//        FilesSettings.instance.root
//    }

}