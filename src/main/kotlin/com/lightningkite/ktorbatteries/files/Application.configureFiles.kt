package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorbatteries.files.MultipartJsonConverter
import com.lightningkite.ktorkmongo.JsonWebSockets
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.routing.*
import io.ktor.serialization.*
import org.apache.commons.vfs2.VFS
import org.apache.commons.vfs2.provider.local.LocalFileSystem

fun Application.configureFiles() {
    routing {
        val root = VFS.getManager().resolveFile(FilesSettings.instance.storageUrl)
        if (root.fileSystem is LocalFileSystem) {
            static("user-content") {
                files(root.path.toString())
            }
        }
    }
}
