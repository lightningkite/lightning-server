package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.serverhealth.HealthStatus

object NoExceptionReporter: ExceptionReporter {
    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(HealthStatus.Level.OK, additionalMessage = "No exception reporting")
    override suspend fun report(t: Throwable, context: Any?): Boolean = false
}