package com.lightningkite.ktorbatteries.files

import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.apache.commons.vfs2.VFS
import java.io.File

@Serializable
data class FilesSettings(
    val storageUrl: String = "file://${File("./local/files").absolutePath}",
    val userContentPath: String = "/user-content"
) : HealthCheckable {
    companion object : SettingSingleton<FilesSettings>()

    init {
        instance = this
    }

    override val healthCheckName: String get() = "Storage"
    override suspend fun healthCheck(): HealthStatus = try {
        files()
            .resolveFile("$storageUrl/healthCheck.txt")
            .use { file ->
                file.content.outputStream
                    .buffered()
                    .use { out -> "Health Check".toByteArray().inputStream().copyTo(out) }
            }
        HealthStatus(HealthStatus.Level.OK)
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }
}

suspend fun files(): CoroutineFileSystemManager = withContext(Dispatchers.IO) {
    @Suppress("BlockingMethodInNonBlockingContext")
    VFS.getManager()
}.coroutine