@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.slf4j.event.Level
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import java.net.NetworkInterface
import java.time.Instant
import kotlin.streams.toList

interface HealthCheckable {
    val healthCheckName: String get() = this::class.simpleName ?: "???"
    suspend fun healthCheck(): HealthStatus
}

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

/**
 * A route for accessing status of features, external service connections, and general server information.
 * Examples of features that can be checked on are Email, Database, and Exception Reporting.
 *
 * @param path The path you wish the endpoint to be at.
 * @param features A list of `HealthCheckable` features that you want reports on.
 */
inline fun <reified USER> ServerPath.healthCheck(features: List<HealthCheckable>, crossinline allowed: suspend (USER)->Boolean = { true }) {
    get.typed(
        summary = "Get Server Health",
        description = "Gets the current status of the server",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit ->
            if(!allowed(user)) throw ForbiddenException()
            val now = Instant.now()
            ServerHealth(
                features = features
                    .associate {
                        ServerHealth.healthCache[it]?.takeIf {
                            now.toEpochMilli() - it.checkedAt.toEpochMilli() < 60_000 && it.level <= HealthStatus.Level.WARNING
                        }?.let { s -> return@associate it.healthCheckName to s }
                        val result = it.healthCheck()
                        ServerHealth.healthCache[it] = result
                        it.healthCheckName to result
                    }
            )
        }
    )
}
