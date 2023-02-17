package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.NotFoundException
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.File
import java.io.IOException
import java.util.*

fun FileObject.resolveRandom(prefix: String = "", extension: String) =
    resolve(prefix + UUID.randomUUID().toString() + ".$extension")

suspend fun FileObject.exists() = info() != null

suspend fun FileObject.local(out: File = File.createTempFile("downloaded", ".temp")): File {
    out.outputStream().use { out ->
        read().use {
            it.copyTo(out)
        }
    }
    return out
}

suspend fun io.ktor.client.statement.HttpResponse.download(
    destination: File = File.createTempFile("temp", this.contentType()?.toString()?.let(::ContentType)?.extension?.let { ".$it" } ?: ""),
): File {
    destination.outputStream().use { out ->
        bodyAsChannel().toInputStream().use {
            it.copyTo(out)
        }
    }
    return destination
}
suspend fun io.ktor.client.statement.HttpResponse.download(
    destination: FileObject
): FileObject {
    destination.write(HttpContent.file(download()))
    return destination
}

val FileObject.serverFile: ServerFile get() = ServerFile(url)

suspend fun FileObject.toHttpContent(): HttpContent? {
    val info = this.info() ?: return null
    return HttpContent.Stream(
        getStream = this::read,
        type = info.type,
        length = info.size
    )
}
suspend fun FileObject.copyTo(other: FileObject) {
    other.write(this.toHttpContent() ?: throw IOException("File disappeared or changed during transfer"))
}