
@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.serverhealth


import com.lightningkite.lightningdb.GenerateDataClassPaths
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant

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

    @Serializable
    @GenerateDataClassPaths
    data class Memory(
        val max: Long,
        val total: Long,
        val free: Long,
        val systemAllocated: Long,
        val usage: Float,
    ) {
    }
}