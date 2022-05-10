package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.apache.commons.vfs2.FileSystemManager
import org.apache.commons.vfs2.VFS
import java.io.File

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files").absolutePath}",
    val userContentPath: String = "user-content",
    val signedUrlExpirationSeconds: Int? = null
) : HealthCheckable {
    companion object : SettingSingleton<FilesSettings>()

    init {
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
