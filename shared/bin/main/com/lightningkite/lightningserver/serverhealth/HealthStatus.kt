@file:SharedCode
@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.lang.management.ManagementFactory
import java.net.NetworkInterface
import java.time.Instant

@Serializable
data class HealthStatus(val level: Level, val checkedAt: Instant = Instant.now(), val additionalMessage: String? = null) {
    @Serializable
    enum class Level(val color: String) {
        OK("green"),
        WARNING("yellow"),
        URGENT("orange"),
        ERROR("red")
    }
}

@Serializable
data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {

    @Serializable
    data class Memory(
        val maxMem: Long,
        val totalMemory: Long,
        val freeMemory: Long,
        val systemAllocated: Long,
        val memUsagePercent: Float,
    ) {
    }
}