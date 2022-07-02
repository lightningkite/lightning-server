package com.lightningkite.lightningserver.files

import com.dalet.vfs2.provider.azure.AzFileProvider
import com.lightningkite.lightningserver.SettingSingleton
import com.lightningkite.lightningserver.auth.AuthSettings
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.routing
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import io.ktor.server.plugins.*
import kotlinx.serialization.Serializable
import org.apache.commons.vfs2.VFS
import org.apache.commons.vfs2.auth.StaticUserAuthenticator
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder
import org.apache.commons.vfs2.provider.local.LocalFileSystem
import java.io.File

/**
 * FileSettings defines where server files and user content is stored. This used ApacheVFS which allows the filesystem to be
 * a variety of sources. For now this is set up to handle a local file system, a s3 bucket, or an azure blob container.
 *
 * @param storageUrl Defines where the file system is. This follows ApacheVFS standards.
 * @param key Used only by Azure right now. Used to authenticate with Azure.
 * @param userContentPath A path you wish all file paths to be prefixed with.
 * @param signedUrlExpirationSeconds When dealing with secured filesystems that require url signing this will determine how long pre-signed URLs will be valid for.
 */

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files").absolutePath}",
    val key: String? = null,
    val signedUrlExpirationSeconds: Int? = null
) : HealthCheckable {
    companion object : SettingSingleton<FilesSettings>() {
        const val userContentPath: String = "user-content"
    }

    init {
        if(storageUrl.startsWith("az")) {
            val auth = StaticUserAuthenticator("", storageUrl.substringAfter("://").substringBefore('.'), this.key ?: throw IllegalStateException("Azure file system requested, but no key was provided."))
            println("Establishing authenticator for Azure as $auth")
            DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(AzFileProvider.getDefaultFileSystemOptions(), auth)
            println(DefaultFileSystemConfigBuilder.getInstance().getUserAuthenticator(AzFileProvider.getDefaultFileSystemOptions()))
        }
        instance = this

        if(root is LocalFileSystem) {
            routing {
                path(userContentPath + "/*").apply{
                    get.handler {
                        if(it.wildcard?.contains('.') != false) throw IllegalStateException()
                        val file = root.resolveFile(it.wildcard).content
                        HttpResponse(
                            body = HttpContent.Stream(
                                getStream = { file.inputStream },
                                length = file.size,
                                type = ContentType(file.contentInfo.contentType)
                            ),
                        )
                    }
                    post.handler {
                        val location = AuthSettings.instance.verify<String>(it.queryParameter("token") ?: throw BadRequestException("No token provided"))
                        if(location != it.wildcard) throw BadRequestException("Token does not match file")
                        if(it.wildcard.contains("..")) throw IllegalStateException()
                        val file = root.resolveFile(it.wildcard).content
                        it.body?.stream()?.copyTo(file.outputStream)
                        HttpResponse(status = HttpStatus.NoContent)
                    }
                }
            }
        }
    }

    override val healthCheckName: String get() = "Storage"
    override suspend fun healthCheck(): HealthStatus = try {
        root
            .resolveFile("healthCheck.txt")
            .use { file ->
                file.content.outputStream
                    .buffered()
                    .use { out -> "Health Check".toByteArray().inputStream().copyTo(out) }
            }
        HealthStatus(HealthStatus.Level.OK)
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    val root get() = VFS.getManager().resolveFile(instance.storageUrl)
}
