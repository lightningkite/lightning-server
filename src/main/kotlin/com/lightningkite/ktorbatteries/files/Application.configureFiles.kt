package com.lightningkite.ktorbatteries.files

import io.ktor.application.*
import io.ktor.http.content.*
import io.ktor.routing.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.vfs2.provider.local.LocalFileSystem

fun Application.configureFiles() {
    routing {
        val root = runBlocking { files().resolveFile(FilesSettings.instance.storageUrl) }
        if (root.fileSystem is LocalFileSystem) {
            static(FilesSettings.instance.userContentPath) {
                files(root.path.toString())
            }
        }
    }
}
