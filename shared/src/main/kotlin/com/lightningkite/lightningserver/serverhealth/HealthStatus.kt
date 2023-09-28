@file:SharedCode
@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.khrysalis.SharedCode
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.lang.management.ManagementFactory
import java.net.NetworkInterface
import kotlinx.datetime.Instant

@Serializable
data class HealthStatus(
    val level: Level,
    val checkedAt: Instant = Clock.System.now(),
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
data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {

    @Serializable
    data class Memory(
        val max: Long,
        val total: Long,
        val free: Long,
        val systemAllocated: Long,
        val usage: Float,
    ) {
    }
}