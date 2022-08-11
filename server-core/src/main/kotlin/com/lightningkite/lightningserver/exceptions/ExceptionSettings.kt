package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.settings.setting
import io.ktor.util.logging.*
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

    init {
//        sentryDsn?.let {
//            Sentry.init(it)
//        }
    }

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
//                Sentry.capture(t)
                true
            }
            else -> false
        }
    }
}

val exceptionSettings = setting("exceptions", ExceptionSettings())