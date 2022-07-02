package com.lightningkite.lightningserver.exceptions

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.SettingSingleton
import com.lightningkite.lightningserver.notifications.ConsoleNotificationInterface
import com.lightningkite.lightningserver.notifications.FcmNotificationInterface
import com.lightningkite.lightningserver.notifications.NotificationImplementation
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningdb.Database
import io.ktor.util.logging.*
import io.sentry.Sentry
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.slf4j.LoggerFactory
import java.io.File

/**
 * ExceptionSettings is used to configure reporting unhandled exceptions to a Sentry server.
 */
@Serializable
data class ExceptionSettings(
    val sentryDsn: String? = null
) : HealthCheckable {
    companion object : SettingSingleton<ExceptionSettings>({ExceptionSettings()})

    init {
        ExceptionSettings.instance = this
        sentryDsn?.let {
            Sentry.init(it)
        }
    }

    override val healthCheckName: String
        get() = "Error Monitoring"

    override suspend fun healthCheck(): HealthStatus {
        val report = try {
            report(Exception("Health Check: Can Report Exception"))
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
        return if (report)
            HealthStatus(HealthStatus.Level.OK)
        else
            HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "Disabled"
            )
    }

    fun report(t: Throwable): Boolean {
        return when {
            sentryDsn != null -> {
                Sentry.capture(t)
                true
            }
            else -> false
        }
    }
}