package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.websocket.WebSockets

interface ExceptionReporter: HealthCheckable {
    suspend fun report(t: Throwable, context: Any? = null): Boolean
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