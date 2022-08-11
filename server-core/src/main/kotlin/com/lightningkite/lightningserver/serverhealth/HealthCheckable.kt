package com.lightningkite.lightningserver.serverhealth

interface HealthCheckable {
    suspend fun healthCheck(): HealthStatus
}