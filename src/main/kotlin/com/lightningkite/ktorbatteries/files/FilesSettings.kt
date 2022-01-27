package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.mongo.MongoSettings
import kotlinx.serialization.Serializable
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.VFS
import java.io.File

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files").absolutePath}",
    val userContentPath: String = "/user-content"
) {
    companion object: SettingSingleton<FilesSettings>()
    init { instance = this }
}

val files: FileSystemManager get() = VFS.getManager()
