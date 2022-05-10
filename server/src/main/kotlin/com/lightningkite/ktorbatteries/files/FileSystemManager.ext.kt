package com.lightningkite.ktorbatteries.files

import com.github.vfss3.S3FileObject
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.provider.local.LocalFile
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.relativeTo

private const val allowedChars = "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM"

val FileObject.publicUrlUnsigned: String
    get() = when(this) {
        is LocalFile -> "${GeneralServerSettings.instance.publicUrl}/${
            path.relativeTo(Path.of(FilesSettings.instance.storageUrl.removePrefix("file://"))).toString()
                .replace("\\", "/")
        }"
        else -> URL("https", url.host, url.port, url.file).toString()
    }

val FileObject.publicUrl: String
    get() = when(this) {
        is LocalFile -> "${GeneralServerSettings.instance.publicUrl}/${
            path.relativeTo(Path.of(FilesSettings.instance.storageUrl.removePrefix("file://"))).toString()
                .replace("\\", "/")
        }"
        is S3FileObject -> {
            FilesSettings.instance.signedUrlExpirationSeconds?.let { seconds ->
                getSignedUrl(seconds)
            } ?: URL("https", url.host, url.port, url.file).toString()
        }
        else -> URL("https", url.host, url.port, url.file).toString()
    }

fun getRandomString(length: Int, allowedChars: String): String = (1..length)
    .map { allowedChars.random() }
    .joinToString("")


fun FileObject.resolveFileWithUniqueName(path: String): FileObject {
    val name = path.substringBeforeLast(".")
    val extension = path.substringAfterLast('.', "")
    var random = ""
    var exists = true
    while (exists) {
        exists = resolveFile("$name$random.$extension").exists()
        if (exists) {
            random = "-${getRandomString(10, allowedChars)}"
        }
    }
    return resolveFile("$name$random.$extension")
}

fun FileObject.upload(stream: InputStream): FileObject {
    this.content.outputStream
        .buffered()
        .use { out -> stream.copyTo(out) }
    return this
}