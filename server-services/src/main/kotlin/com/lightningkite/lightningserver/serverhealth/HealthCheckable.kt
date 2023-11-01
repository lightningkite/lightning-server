package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.lightningserver.serverhealth.HealthStatus

interface HealthCheckable {
    suspend fun healthCheck(): HealthStatus
}