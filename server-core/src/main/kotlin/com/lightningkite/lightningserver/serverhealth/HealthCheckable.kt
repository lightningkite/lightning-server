package com.lightningkite.lightningserver.serverhealth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface HealthCheckable {
    val healthCheckFrequency: Duration get() = 1.minutes
    suspend fun healthCheck(): HealthStatus
}