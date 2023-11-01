package com.lightningkite.lightningserver.files

import com.lightningkite.Blob
import com.lightningkite.MimeType
import com.lightningkite.lightningserver.encryption.signUrl
import com.lightningkite.lightningserver.encryption.verifyUrl
import com.lightningkite.now
import java.io.File
import kotlin.time.Duration
import kotlinx.datetime.Instant

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
        return FileInfo(type = contentTypeFile.takeIf { it.exists() }?.readText()?.let { MimeType(it) }
            ?: MimeType.fromExtension(file.extension),
            size = file.length(),
            lastModified = Instant.fromEpochMilliseconds(file.lastModified()))
    }

    override suspend fun put(content: Blob) {
        file.parentFile.mkdirs()
        contentTypeFile.writeText(content.type.toString())
        file.outputStream().use { o ->
            content.read().use { i ->
                i.copyTo(o)
            }
        }
    }

    override suspend fun get(): Blob? = if(file.exists()) Blob(
        file,
        contentTypeFile.takeIf { it.exists() }?.readText()?.let { MimeType(it) } ?: MimeType.fromExtension(file.extension)
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
                system.signer.verifyUrl(signedUrlStart, qp["signature"] ?: "")
            } ?: true
        } catch (e: Exception) {
            false
        }
    }
    override fun checkSignature(queryParams: String): Boolean = checkSignature(queryParams.split('&').map { it.substringBefore('=') to it.substringAfter('=') })

    override val url: String
        get() = system.hostedAt + "/" + file.absoluteFile.relativeTo(
            system.rootFile
        ).unixPath

    override val signedUrl: String
        get() = if(system.signedUrlExpiration == null) url else url.plus("?readUntil=${now().plus(system.signedUrlExpiration).toEpochMilliseconds()}").let {
            it + "&signature=" + system.signer.signUrl(it)
        }

    override fun uploadUrl(timeout: Duration): String = url.plus("?writeUntil=${now().plus(timeout).toEpochMilliseconds()}").let {
        it + "&signature=" + system.signer.signUrl(it)
    }

    internal fun checkSignatureWrite(queryParams: List<Pair<String, String>>): Boolean {
        return try {
            val qp = queryParams.associate { it }
            val writeUntil = qp["writeUntil"]?.toLongOrNull() ?: return false
            val signedUrlStart = "$url?writeUntil=$writeUntil"
            if(System.currentTimeMillis() > writeUntil) return false
            system.signer.verifyUrl(signedUrlStart, qp["signature"] ?: "")
        } catch (e: Exception) {
            false
        }
    }

    override fun toString(): String = file.toString()

    override fun hashCode(): Int = file.hashCode()

    override fun equals(other: Any?): Boolean = other is LocalFile && this.file == other.file
}