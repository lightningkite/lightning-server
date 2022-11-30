package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.settings.generalSettings
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import kotlin.io.path.inputStream

val File.unixPath: String get() = path.replace("\\", "/")

class LocalFile(val system: LocalFileSystem, val file: File) : FileObject {
    init {
        if (!file.absolutePath.startsWith(system.rootFile.absolutePath)) throw IllegalStateException()
    }

    override fun resolve(path: String): FileObject = LocalFile(system, file.resolve(path).absoluteFile)

    val contentTypeFile = file.parentFile!!.resolve(file.name + ".contenttype")

    override val parent: FileObject?
        get() = if (this.file == system.rootFile) null else LocalFile(
            system, file.parentFile
        )

    override suspend fun list(): List<FileObject>? = if (!file.isDirectory) null else file.listFiles()?.map {
        LocalFile(
            system, it
        )
    }

    override suspend fun info(): FileInfo? {
        if (!file.exists()) return null
        return FileInfo(type = contentTypeFile.takeIf { it.exists() }?.readText()?.let { ContentType(it) }
            ?: ContentType.fromExtension(file.extension),
            size = file.length(),
            lastModified = Instant.ofEpochMilli(file.lastModified()))
    }

    override suspend fun write(content: HttpContent) {
        file.parentFile.mkdirs()
        contentTypeFile.writeText(content.type.toString())
        file.outputStream().use { o ->
            content.stream().use { i ->
                i.copyTo(o)
            }
        }
    }

    override suspend fun read(): InputStream = file.toPath().inputStream()

    override suspend fun delete() {
        if (contentTypeFile.exists()) contentTypeFile.delete()
        assert(file.delete())
    }

    override fun checkSignature(queryParams: String): Boolean {
        return try {
            system.signer.verify<String>(queryParams.substringAfter('=')) == file.absoluteFile.relativeTo(system.rootFile).unixPath
        } catch (e: Exception) {
            false
        }
    }

    override val url: String
        get() = generalSettings().publicUrl + "/" + system.serveDirectory + "/" + file.absoluteFile.relativeTo(
            system.rootFile
        ).unixPath

    override val signedUrl: String get() = url + "?token=" + system.signer.token(file.absoluteFile.relativeTo(system.rootFile).unixPath)

    override fun uploadUrl(timeout: Duration): String =
        url + "?token=" + system.signer.token("W|" + file.absoluteFile.relativeTo(system.rootFile).unixPath, timeout)

    override fun toString(): String = file.toString()

    override fun hashCode(): Int = file.hashCode()

    override fun equals(other: Any?): Boolean = other is LocalFile && this.file == other.file
}