package com.lightningkite.ktorbatteries.files

import com.dalet.vfs2.provider.azure.AzConstants
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.vfs2.provider.local.LocalFileSystem

fun Application.configureFiles() {
    routing {
        val root = FilesSettings.instance.root
        if (root.fileSystem is LocalFileSystem) {
            static(FilesSettings.instance.userContentPath) {
                files(root.path.toString())
            }
            post(FilesSettings.instance.userContentPath + "/{fileName}") {
                val file = root.resolveFile(call.parameters["fileName"]!!)
                call.receiveStream().copyTo(file.content.outputStream)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
