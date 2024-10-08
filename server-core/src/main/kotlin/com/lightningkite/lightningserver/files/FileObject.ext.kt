package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.uuid
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.File
import java.io.IOException
import java.util.*
import com.lightningkite.UUID

fun FileObject.resolveRandom(prefix: String = "", extension: String) =
    resolve(prefix + uuid().toString() + ".$extension")

suspend fun FileObject.exists() = head() != null

suspend fun FileObject.local(@Suppress("BlockingMethodInNonBlockingContext") out: File = File.createTempFile("downloaded", ".temp")): File {
    out.outputStream().use { outStream ->
        get()!!.stream().use {
            it.copyTo(outStream)
        }
    }
    return out
}

suspend fun io.ktor.client.statement.HttpResponse.download(
    destination: File = File.createTempFile(
        "temp",
        this.contentType()?.toString()?.let(::ContentType)?.extension?.let { ".$it" } ?: ""),
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
    destination.put(HttpContent.file(download()))
    return destination
}

val FileObject.serverFile: ServerFile get() = ServerFile(url)

suspend fun FileObject.toHttpContent(): HttpContent? = this.get()

val FileObject.nameWithoutExtension: String get() = this.name.substringBeforeLast('.')
val FileObject.extension: String get() = this.name.substringAfterLast('.', "")