package com.lightningkite.ktorbatteries.exceptions

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.notifications.ConsoleNotificationInterface
import com.lightningkite.ktorbatteries.notifications.FcmNotificationInterface
import com.lightningkite.ktorbatteries.notifications.NotificationImplementation
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import com.lightningkite.ktordb.Database
import io.ktor.util.logging.*
import io.sentry.Sentry
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class ExceptionSettings(
    val sentryDsn: String? = null
) : HealthCheckable {
    companion object : SettingSingleton<ExceptionSettings>()

    init {
        ExceptionSettings.instance = this
        sentryDsn?.let {
            Sentry.init(it)
        }
    }

    override val healthCheckName: String
        get() = "Error Monitoring"
    override suspend fun healthCheck(): HealthStatus {
        return when {
            sentryDsn != null ->
                try {
                    val e = Exception("Health Check: Can Report Exception")
                    Sentry.capture(e)
                    HealthStatus(HealthStatus.Level.OK)
                } catch (e: Exception) {
                    HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
                }
            else -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Disabled")
        }
    }
}