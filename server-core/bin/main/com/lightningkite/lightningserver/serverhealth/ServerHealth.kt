package com.lightningkite.lightningserver.serverhealth

import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.net.NetworkInterface

@Serializable
data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {
    constructor(
        features: Map<String, HealthStatus>,
    ) : this(
        serverId = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")?.takeUnless { it.isEmpty() } ?: NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name } .firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?",
        version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")?.takeUnless { it.isEmpty() } ?: "Unknown",
        memory = Memory(),
        features = features,
        loadAverageCpu = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage,
    )
    companion object {
        val healthCache = HashMap<HealthCheckable, HealthStatus>()
    }

    @Serializable
    data class Memory(
        val maxMem: Long,
        val totalMemory: Long,
        val freeMemory: Long,
        val systemAllocated: Long,
        val memUsagePercent: Float,
    ) {
        constructor() : this(
            maxMem = Runtime.getRuntime().maxMemory(),
            totalMemory = Runtime.getRuntime().totalMemory(),
            freeMemory = Runtime.getRuntime().freeMemory(),
            systemAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            memUsagePercent = ((((Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
                .freeMemory()).toFloat() / Runtime.getRuntime().maxMemory().toFloat() * 100f) * 100).toInt()) / 100f,
        )
    }
}