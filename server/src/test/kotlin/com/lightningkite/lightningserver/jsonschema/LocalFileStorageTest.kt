package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.publicUrl
import com.lightningkite.lightningserver.files.upload
import com.lightningkite.lightningserver.settings.GeneralServerSettings
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