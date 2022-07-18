package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.settings.generalSettings
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.io.path.inputStream

class LocalFile(val system: LocalFileSystem, val file: File): FileObject {
    init {
        if(!file.absolutePath.startsWith(system.rootFile.absolutePath)) throw IllegalStateException()
    }
    override fun resolve(path: String): FileObject = LocalFile(system, file.resolve(path).absoluteFile)

    override val parent: FileObject? get() = if(this.file == system.rootFile) null else LocalFile(
        system,
        file.parentFile
    )
    override suspend fun list(): List<FileObject>? = if(!file.isDirectory) null else file.listFiles()?.map {
        LocalFile(
            system,
            it
        )
    }

    override suspend fun info(): FileInfo? {
        if(!file.exists()) return null
        return FileInfo(
            type = ContentType.fromExtension(file.extension),
            size = file.length(),
            lastModified = Instant.ofEpochMilli(file.lastModified())
        )
    }

    override suspend fun write(content: HttpContent) {
        file.parentFile.mkdirs()
        file.outputStream().use { o ->
            content.stream().use { i ->
                i.copyTo(o)
            }
        }
    }

    override suspend fun read(): InputStream = file.toPath().inputStream()

    override suspend fun delete() { assert(file.delete()) }

    override fun checkSignature(queryParams: String): Boolean {
        return try {
            system.signer.verify<String>(queryParams.substringAfter('=')) == file.relativeTo(system.rootFile).path
        } catch(e: Exception) {
            false
        }
    }

    override val url: String get() = generalSettings().publicUrl + "/" + system.serveDirectory + "/" + file.relativeTo(system.rootFile).path

    override val signedUrl: String get() = system.signer.token(file.relativeTo(system.rootFile))

    override fun uploadUrl(timeoutMilliseconds: Long): String = system.signer.token(file.relativeTo(system.rootFile), timeoutMilliseconds)

    override fun toString(): String = file.toString()

    override fun hashCode(): Int = file.hashCode()

    override fun equals(other: Any?): Boolean = other is LocalFile && this.file == other.file
}