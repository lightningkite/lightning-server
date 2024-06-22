package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.utils.parseParameterString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import kotlin.time.Duration
import kotlinx.datetime.Instant

class Sftp(
    val host: String,
    val port: Int,
    val rootPath: String,
    val getClient: () -> SSHClient,
) : FileSystem {

    companion object {
        init {
            FilesSettings.register("sftp") { settings ->
                Regex("""sftp://(?<user>[^@]+)@(?<host>[^:]+):(?<port>[0-9]+)/(?<path>[^?]*)\?(?<params>.*)""").matchEntire(
                    settings.url
                )?.let { match ->
                    val host = match.groups["host"]!!.value
                    val port = match.groups["port"]!!.value.toInt()
                    val params: Map<String, List<String>> =
                        parseParameterString(match.groups["params"]!!.value)
                    val rootPath = match.groups["path"]!!.value
                    val user = match.groups["user"]!!.value
                    Sftp(host, port, rootPath) {
                        SSHClient(DefaultConfig().apply {
                            if (params["algorithm"]?.firstOrNull() == "ssh-rsa")
                                prioritizeSshRsaKeyAlgorithm()
                        }).apply {
                            params["host"]?.let { addHostKeyVerifier(it.first()) } ?: addHostKeyVerifier(
                                PromiscuousVerifier()
                            )
                            connect(host, port)
                            params["identity"]?.firstOrNull()?.let { id ->
                                val pk = buildString {
                                    appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
                                    id.chunked(70).forEach { l -> appendLine(l) }
                                    appendLine("-----END OPENSSH PRIVATE KEY-----")
                                }
                                authPublickey(user, loadKeys(pk, null, null))
                            } ?: params["password"]?.firstOrNull()?.let {
                                authPassword(user, it)
                            }
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
        override val name: String
            get() = path.name

        val sftpPath = if (path.path.startsWith('/')) path.path else "./${path.path}"

        override val parent: FileObject?
            get() = path.parentFile?.let { SftpFile(system, it) }
                ?: system.root

        override suspend fun list(): List<FileObject>? = withContext(Dispatchers.IO) {
            system.withClient {
                try {
                    ls(sftpPath)?.map { SftpFile(system, path.resolve(it.path).relativeTo(File(system.rootPath))) }
                } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                    if (e.message == "No such file") null
                    else throw e
                }
            }
        }

        override suspend fun head(): FileInfo? = withContext(Dispatchers.IO) {
            system.withClient {
                try {
                    this.stat(sftpPath)?.let {
                        FileInfo(
                            type = path.extension.let { ContentType.fromExtension(it) },
                            size = it.size,
                            lastModified = Instant.fromEpochSeconds(it.mtime)
                        )
                    }
                } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                    if (e.message == "No such file") null
                    else throw e
                }
            }
        }

        override suspend fun put(content: HttpContent) = withContext(Dispatchers.IO) {
            val stream = content.stream()
            system.withClient {
                path.parent?.let { this.mkdirs(it) }
                stream.use { input ->
                    open(sftpPath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use {
                        it.RemoteFileOutputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            Unit
        }

        override suspend fun get(): HttpContent? = withContext(Dispatchers.IO) {
            val file = File.createTempFile("temp", ".file")
            system.withClient {
                println("$sftpPath -> ${file.path}")
                file.outputStream().use { out ->
                    open(sftpPath, setOf(OpenMode.READ)).use {
                        it.RemoteFileInputStream().use {
                            it.copyTo(out)
                        }
                    }
                }
            }
            HttpContent.file(
                file,
                ContentType.fromExtension(sftpPath.substringAfterLast('.'))
            )
        }

        override suspend fun delete() = withContext(Dispatchers.IO) {
            system.withClient {
                this.rm(sftpPath)
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
