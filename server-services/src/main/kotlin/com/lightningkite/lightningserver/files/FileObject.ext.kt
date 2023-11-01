package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.uuid
import java.io.File
import java.io.IOException

fun FileObject.resolveRandom(prefix: String = "", extension: String) =
    resolve(prefix + uuid().toString() + ".$extension")

suspend fun FileObject.exists() = head() != null

suspend fun FileObject.local(out: File = File.createTempFile("downloaded", ".temp")): File {
    out.outputStream().use { out ->
        get()!!.read().use {
            it.copyTo(out)
        }
    }
    return out
}

val FileObject.serverFile: ServerFile get() = ServerFile(url)

suspend fun FileObject.copyTo(other: FileObject) {
    other.put(this.get() ?: throw IOException("File disappeared or changed during transfer"))
}

val FileObject.nameWithoutExtension: String get() = this.name.substringBeforeLast('.')
val FileObject.extension: String get() = this.name.substringAfterLast('.', "")