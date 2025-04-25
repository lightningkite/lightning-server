
@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.serverhealth


import com.lightningkite.lightningserver.db.GenerateDataClassPaths
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import kotlin.math.roundToInt

@Serializable
@GenerateDataClassPaths
data class HealthStatus(
    val level: Level,
    val checkedAt: Instant = now(),
    val additionalMessage: String? = null
) {
    @Serializable
    enum class Level(val color: String) {
        OK("green"),
        WARNING("yellow"),
        URGENT("orange"),
        ERROR("red")
    }
}

@Serializable
@GenerateDataClassPaths
data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {
    val overall: HealthStatus.Level get() = features.maxOf { it.value.level }
    val loadAverageCpuHealth get() = when(val amount = loadAverageCpu ) {
        in 0.0 ..< 0.7 -> HealthStatus(HealthStatus.Level.OK)
        in 0.7 ..< 0.95 -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%")
        in 0.95 ..< 1.0 -> HealthStatus(HealthStatus.Level.URGENT, additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%")
        else -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%")
    }

    @Serializable
    @GenerateDataClassPaths
    data class Memory(
        val max: Long,
        val total: Long,
        val free: Long,
        val systemAllocated: Long,
        val usage: Float,
    ) {
        val status get() = when(val amount = usage) {
            in 0f ..< 0.7f -> HealthStatus(HealthStatus.Level.OK)
            in 0.7f ..< 0.95f -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%")
            in 0.95f ..< 1f -> HealthStatus(HealthStatus.Level.URGENT, additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%")
            else -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%")
        }
    }
}