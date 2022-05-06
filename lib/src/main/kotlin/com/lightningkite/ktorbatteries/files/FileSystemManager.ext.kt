package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.provider.local.LocalFile
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.relativeTo

private const val allowedChars = "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM"

val FileObject.publicUrl: String
    get() = if (this is LocalFile)
        "${GeneralServerSettings.instance.publicUrl}/${
            path.relativeTo(Path.of(FilesSettings.instance.storageUrl.removePrefix("file://"))).toString()
                .replace("\\", "/")
        }"
    else
        URL("https", url.host, url.port, url.file).toString()

fun getRandomString(length: Int, allowedChars: String): String = (1..length)
    .map { allowedChars.random() }
    .joinToString("")


suspend fun CoroutineFileSystemManager.resolveFileWithUniqueName(path: String): FileObject {
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

suspend fun CoroutineFileSystemManager.uploadUnique(stream: InputStream, path: String): FileObject =
    files()
        .resolveFileWithUniqueName(path)
        .use { file ->
            file.content.outputStream
                .buffered()
                .use { out -> stream.copyTo(out) }
            file
        }

suspend fun CoroutineFileSystemManager.upload(stream: InputStream, path: String): FileObject =
    @Suppress("BlockingMethodInNonBlockingContext")
    files()
        .resolveFile(path)
        .use { file ->
            file.content.outputStream
                .buffered()
                .use { out -> stream.copyTo(out) }
            file
        }