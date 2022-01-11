package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.VFS
import org.apache.commons.vfs2.provider.local.LocalFile
import java.io.InputStream
import java.net.URL


val FileObject.publicUrl: String
    get() = if (this is LocalFile) "${GeneralServerSettings.instance.publicUrl}user-content/${
        path.toString().replace("\\", "/")
            .removePrefix(FilesSettings.instance.storageUrl.replace("\\", "/").removePrefix("file:///"))
    }"
    else URL("https", url.host, url.port, url.file).toString()

fun getRandomString(length: Int, allowedChars: List<Char>): String = (1..length)
    .map { allowedChars.random() }
    .joinToString("")


suspend fun FileSystemManager.resolveFileWithUniqueName(path: String): FileObject {
    val allowedChars: List<Char> = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    val name = path.substringBeforeLast(".")
    val extension = path.substringAfterLast('.', "")
    var random = ""
    var exists = true
    while (exists) {
        withContext(Dispatchers.IO) {
            @Suppress("BlockingMethodInNonBlockingContext")
            exists = resolveFile("$name$random.$extension").exists()
        }
        if (exists) {
            random = "-${getRandomString(10, allowedChars)}"
        }
    }
    return withContext(Dispatchers.IO) {
        @Suppress("BlockingMethodInNonBlockingContext")
        resolveFile("$name$random.$extension")
    }
}

suspend fun FileSystemManager.uploadUnique(stream: InputStream, path: String): FileObject =
    withContext(Dispatchers.IO) {
        @Suppress("BlockingMethodInNonBlockingContext")
        VFS.getManager()
    }
        .resolveFileWithUniqueName(path)
        .use { file ->
            file.content.outputStream
                .buffered()
                .use { out -> stream.copyTo(out) }
            file
        }

suspend fun FileSystemManager.upload(stream: InputStream, path: String): FileObject =
    withContext(Dispatchers.IO) {
        @Suppress("BlockingMethodInNonBlockingContext")
        VFS.getManager().resolveFile(path)
    }
        .use { file ->
            file.content.outputStream
                .buffered()
                .use { out -> stream.copyTo(out) }
            file
        }