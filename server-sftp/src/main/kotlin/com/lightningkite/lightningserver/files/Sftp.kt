package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class Sftp(
    val host: String,
    val port: Int,
    val rootPath: String,
    val getClient: () -> SSHClient,
) : FileSystem {

    companion object {
        init {
            FilesSettings.register("sftp") { settings ->
                val postScheme = settings.storageUrl.substringAfter("://")
                val userAndHost = postScheme.substringBefore("/")
                val user = userAndHost.substringBefore('@', "ubuntu")
                val hostWithPort = userAndHost.substringAfter('@')
                val host = hostWithPort.substringBefore(':')
                val port = hostWithPort.substringAfter(':', "22").toInt()
                val rootPath = postScheme.substringAfter('/').substringBefore('?')
                val params = postScheme.substringAfter('/').substringAfter('?').split('&')
                    .associate { it.substringBefore('=') to it.substringAfter('=', "true") }
                Sftp(host, port, rootPath) {
                    SSHClient().apply {
                        params["host"]?.let { addHostKeyVerifier(it) } ?: addHostKeyVerifier(PromiscuousVerifier())
                        connect(host, port)
                        val id = params["identity"]!!
                        val pk = buildString {
                            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
                            id.chunked(70).forEach { l -> appendLine(l) }
                            appendLine("-----END OPENSSH PRIVATE KEY-----")
                        }
                        authPublickey(user, loadKeys(pk, null, null))
                    }
                }
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
                        type = path.extension.let { println("Extension is $it"); ContentType.fromExtension(it).also { println(it) } },
                        size = it.size,
                        lastModified = Instant.ofEpochSecond(it.mtime)
                    )
                }
            }
        }

        override suspend fun write(content: HttpContent) = withContext(Dispatchers.IO) {
            system.withClient {
                val file = File.createTempFile("temp", ".file")
                content.stream().use {
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
