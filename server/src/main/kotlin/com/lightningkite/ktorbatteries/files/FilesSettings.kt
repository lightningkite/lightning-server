package com.lightningkite.ktorbatteries.files

import com.dalet.vfs2.provider.azure.AzFileProvider
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.FileSystemOptions
import org.apache.commons.vfs2.VFS
import org.apache.commons.vfs2.auth.StaticUserAuthenticator
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder
import java.io.File

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files").absolutePath}",
    val key: String? = null,
    val userContentPath: String = "user-content",
    val signedUrlExpirationSeconds: Int? = null
) : HealthCheckable {
    companion object : SettingSingleton<FilesSettings>()

    init {
        if(storageUrl.startsWith("az")) {
            val auth = StaticUserAuthenticator("", storageUrl.substringAfter("://").substringBefore('.'), this.key ?: throw IllegalStateException("Azure file system requested, but no key was provided."))
            println("Establishing authenticator for Azure as $auth")
            DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(AzFileProvider.getDefaultFileSystemOptions(), auth)
            println(DefaultFileSystemConfigBuilder.getInstance().getUserAuthenticator(AzFileProvider.getDefaultFileSystemOptions()))
        }
        instance = this
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
