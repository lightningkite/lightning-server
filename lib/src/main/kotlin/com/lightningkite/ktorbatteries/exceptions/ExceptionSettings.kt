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
import com.lightningkite.ktorkmongo.Database
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

@Serializable
data class ExceptionSettings(
    val sentryDsn: String? = null
) : HealthCheckable {
    companion object : SettingSingleton<ExceptionSettings>()

    init {
        ExceptionSettings.instance = this
        sentryDsn?.let {
            Sentry.init { options ->
                options.dsn = it
            }
        }
    }

    override val healthCheckName: String
        get() = "Error Monitoring"
    override suspend fun healthCheck(): HealthStatus {
        return when {
            sentryDsn != null ->
                try {
                    Sentry.captureMessage("Sentry Health Check", SentryLevel.DEBUG)
                    HealthStatus(HealthStatus.Level.OK)
                } catch (e: Exception) {
                    HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
                }
            else -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Disabled")
        }
    }
}