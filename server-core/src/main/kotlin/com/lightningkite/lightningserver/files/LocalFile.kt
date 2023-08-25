package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.sign
import com.lightningkite.lightningserver.auth.verify
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.settings.generalSettings
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import kotlin.io.path.inputStream

val File.unixPath: String get() = path.replace("\\", "/")

/**
 * A FileObject implementation to points to a File in the environments local file system.
 */
class LocalFile(val system: LocalFileSystem, val file: File) : FileObject {
    init {
        if (!file.absolutePath.startsWith(system.rootFile.absolutePath)) throw IllegalStateException()
    }
    override val name: String
        get() = file.name

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

    override suspend fun head(): FileInfo? {
        if (!file.exists()) return null
        return FileInfo(type = contentTypeFile.takeIf { it.exists() }?.readText()?.let { ContentType(it) }
            ?: ContentType.fromExtension(file.extension),
            size = file.length(),
            lastModified = Instant.ofEpochMilli(file.lastModified()))
    }

    override suspend fun put(content: HttpContent) {
        file.parentFile.mkdirs()
        contentTypeFile.writeText(content.type.toString())
        file.outputStream().use { o ->
            content.stream().use { i ->
                i.copyTo(o)
            }
        }
    }

    override suspend fun get(): HttpContent? = if(file.exists()) HttpContent.file(
        file,
        contentTypeFile.takeIf { it.exists() }?.readText()?.let { ContentType(it) } ?: ContentType.fromExtension(file.extension)
    ) else null

    override suspend fun delete() {
        if (contentTypeFile.exists()) contentTypeFile.delete()
        assert(file.delete())
    }

    fun checkSignature(queryParams: List<Pair<String, String>>): Boolean {
        return try {
            system.signedUrlExpiration?.let {
                val qp = queryParams.associate { it }
                val readUntil = qp["readUntil"]?.toLongOrNull() ?: return false
                if(System.currentTimeMillis() > readUntil) return false
                val signedUrlStart = "$url?readUntil=$readUntil"
                system.signer.verify(signedUrlStart, qp["signature"] ?: "")
            } ?: true
        } catch (e: Exception) {
            false
        }
    }
    override fun checkSignature(queryParams: String): Boolean = checkSignature(queryParams.split('&').map { it.substringBefore('=') to it.substringAfter('=') })

    override val url: String
        get() = generalSettings().publicUrl + "/" + system.serveDirectory + "/" + file.absoluteFile.relativeTo(
            system.rootFile
        ).unixPath

    override val signedUrl: String
        get() = if(system.signedUrlExpiration == null) url else url.plus("?readUntil=${Instant.now().plus(system.signedUrlExpiration).toEpochMilli()}").let {
            it + "&signature=" + system.signer.sign(it)
        }

    override fun uploadUrl(timeout: Duration): String = url.plus("?writeUntil=${Instant.now().plus(timeout).toEpochMilli()}").let {
        println("SIGNED $it")
        it + "&signature=" + system.signer.sign(it)
    }

    internal fun checkSignatureWrite(queryParams: List<Pair<String, String>>): Boolean {
        return try {
            val qp = queryParams.associate { it }
            val writeUntil = qp["writeUntil"]?.toLongOrNull() ?: return false
            val signedUrlStart = "$url?writeUntil=$writeUntil"
            if(System.currentTimeMillis() > writeUntil) return false
            println("CHECK $signedUrlStart")
            system.signer.verify(signedUrlStart, qp["signature"] ?: "")
        } catch (e: Exception) {
            false
        }
    }

    override fun toString(): String = file.toString()

    override fun hashCode(): Int = file.hashCode()

    override fun equals(other: Any?): Boolean = other is LocalFile && this.file == other.file
}