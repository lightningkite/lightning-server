package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

/**
 * An abstracted model for handling exception reporting.
 * Every implementation will handle how and where to submit unhandled exception reports.
 */
interface ExceptionReporter : HealthCheckable {

    /**
     * The function that makes the reports to the underlying service.
     */
    suspend fun report(t: Throwable, context: Any? = null): Boolean

    /**
     * Will attempt to send a report to confirm that the service is alive and available.
     */
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
}