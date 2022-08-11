package com.lightningkite.lightningserver.serverhealth

import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.net.NetworkInterface

@Serializable
data class ServerHealth(
    val serverId: String = NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }.firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?",
    val memory: Memory = Memory(),
    val features: Map<String, HealthStatus> = mapOf(),
    val loadAverageCpu: Double = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
) {
    companion object {
        val healthCache = HashMap<HealthCheckable, HealthStatus>()
    }
    @Serializable
    data class Memory(
        val maxMem: Long = Runtime.getRuntime().maxMemory(),
        val totalMemory: Long = Runtime.getRuntime().totalMemory(),
        val freeMemory: Long = Runtime.getRuntime().freeMemory(),
        val systemAllocated: Long = totalMemory - freeMemory,
        val memUsagePercent: Float = (((systemAllocated.toFloat() / maxMem.toFloat() * 100f) * 100).toInt()) / 100f
    )
}