package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.typed.api
import kotlinx.coroutines.withTimeoutOrNull
import com.lightningkite.now
import kotlinx.serialization.builtins.serializer
import java.lang.management.ManagementFactory
import java.net.NetworkInterface

/**
 * A route for accessing status of features, external service connections, and general server information.
 * Examples of features that can be checked on are Email, Database, and Exception Reporting.
 */
fun ServerPath.healthCheck(
    cache: () -> Cache = Settings.requirements["cache"]?.let { { it.invoke() as? Cache ?: LocalCache } } ?: { LocalCache },
) = get.api(
    authOptions = noAuth,
    inputType = Unit.serializer(),
    outputType = ServerHealth.serializer(),
    summary = "Get Server Health",
    description = "Gets the current status of the server",
    errorCases = listOf(),
    implementation = { _: Unit ->
        val now = now()
        serverHealth(
            features = Settings.requirements.mapValues { it.value() }.entries.mapNotNull {
                val checkable =
                    it.value as? HealthCheckable ?: return@mapNotNull null
                it.key to checkable
            }.associate { it }.mapValues { (key, checkable) ->
                cache().get(key, HealthStatus.serializer())
                    ?.takeIf { now() - it.checkedAt < checkable.healthCheckFrequency }
                    ?: withTimeoutOrNull(10_000L) { checkable.healthCheck() }?.also {
                        cache().set(key, it, HealthStatus.serializer(), timeToLive = checkable.healthCheckFrequency)
                    }
                    ?: HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Timed out after 10 seconds.")

            }
        )
    }
)

private fun serverHealth(
    features: Map<String, HealthStatus>,
): ServerHealth = ServerHealth(
    serverId = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")?.takeUnless { it.isEmpty() }
        ?: NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }
            .firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?",
    version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")?.takeUnless { it.isEmpty() } ?: "Unknown",
    memory = memory(),
    features = features,
    loadAverageCpu = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage / ManagementFactory.getOperatingSystemMXBean().availableProcessors,
)

private fun Long.roundMemoryForSecurity() = this.div(100_000).times(100_000)  // Round to the nearest megabyte
private fun memory(): ServerHealth.Memory {
    val max = Runtime.getRuntime().maxMemory().roundMemoryForSecurity()
    val total = Runtime.getRuntime().totalMemory().roundMemoryForSecurity()
    val free = Runtime.getRuntime().freeMemory().roundMemoryForSecurity()
    return ServerHealth.Memory(
        max = max,
        total = total,
        free = free,
        systemAllocated = total - free,
        usage = ((total - free).toDouble() / max.toDouble()).toFloat()
    )
}