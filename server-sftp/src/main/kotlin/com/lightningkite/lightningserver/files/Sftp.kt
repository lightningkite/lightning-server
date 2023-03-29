package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant

class Sftp(
    val host: String,
    val port: Int,
    val rootPath: String,
    val getClient: () -> SSHClient,
) : FileSystem {

    companion object {
        init {
            FilesSettings.register("sftp") { settings ->
                Regex("""sftp://(?<user>[^@]+)@(?<host>[^:]+):(?<port>[0-9]+)/(?<path>[^?]*)\?(?<params>.*)""").matchEntire(settings.storageUrl)?.let { match ->
                    val host = match.groups["host"]!!.value
                    val port = match.groups["port"]!!.value.toInt()
                    val params: Map<String, List<String>> = FilesSettings.parseParameterString(match.groups["params"]!!.value)
                    val rootPath = match.groups["path"]!!.value
                    val user = match.groups["user"]!!.value
                    Sftp(host, port, rootPath) {
                        SSHClient().apply {
                            params["host"]?.let { addHostKeyVerifier(it.first()) } ?: addHostKeyVerifier(PromiscuousVerifier())
                            connect(host, port)
                            val id = params["identity"]!!.first()
                            val pk = buildString {
                                appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
                                id.chunked(70).forEach { l -> appendLine(l) }
                                appendLine("-----END OPENSSH PRIVATE KEY-----")
                            }
                            authPublickey(user, loadKeys(pk, null, null))
                        }
                    }
                }
                    ?: throw IllegalStateException("Invalid sftp storageUrl. The URL should match the pattern: sftp://[user]@[host]:[port]/[path]?[params]\nParams available are: host, identity.")
            }
        }
    }

    fun <T> withClient(action: SFTPClient.() -> T): T {
        val client = getClient()
        val sftp = client.newSFTPClient()
        val result = action(sftp)
        sftp.close()
        client.close()
        return result
    }

    override val root: FileObject = SftpFile(this, File(rootPath))
    override val rootUrls: List<String> get() = listOf()

    override suspend fun healthCheck(): HealthStatus {
        return super.healthCheck()
    }

    data class SftpFile(val system: Sftp, val path: File) : FileObject {
        override fun resolve(path: String): FileObject = SftpFile(system, this.path.resolve(path))

        override val parent: FileObject?
            get() = path.parentFile?.let { SftpFile(system, it) }
                ?: if (path.unixPath.isNotEmpty()) system.root else null

        override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
            system.withClient {
                try {
                    ls(path.path)?.map { SftpFile(system, path.resolve(it.path)) }
                } catch(e: net.schmizz.sshj.sftp.SFTPException) {
                    if(e.message == "No such file") null
                    else throw e
                }
            }
        }

        override suspend fun info(): FileInfo? = withContext(Dispatchers.IO) {
            system.withClient {
                this.stat(path.path)?.let {
                    FileInfo(
                        type = path.extension.let { ContentType.fromExtension(it) },
                        size = it.size,
                        lastModified = Instant.ofEpochSecond(it.mtime)
                    )
                }
            }
        }

        override suspend fun write(content: HttpContent) = withContext(Dispatchers.IO) {
            val stream = content.stream()
            system.withClient {
                val file = File.createTempFile("temp", ".file")
                stream.use {
                    file.outputStream().use { o ->
                        it.copyTo(o)
                    }
                }
                this.mkdirs(path.parent)
                this.put(file.path, path.path)
            }
        }

        override suspend fun read(): InputStream = withContext(Dispatchers.IO) {
            val file = File.createTempFile("temp", ".file")
            system.withClient {
                this.get(path.path, file.path)
            }
            file.inputStream()
        }

        override suspend fun delete() = withContext(Dispatchers.IO) {
            system.withClient {
                this.rm(path.path)
            }
        }

        override val url: String
            get() = "sftp://${system.host}:${system.port}/${path.path}"

        override val signedUrl: String
            get() = throw UnsupportedOperationException()

        override fun uploadUrl(timeout: Duration): String = throw UnsupportedOperationException()
        override fun toString(): String = url
    }
}
