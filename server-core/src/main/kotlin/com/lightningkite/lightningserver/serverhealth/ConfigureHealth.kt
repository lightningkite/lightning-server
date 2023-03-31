package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.typed.ApiEndpoint0
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import java.lang.management.ManagementFactory
import java.net.NetworkInterface
import java.time.Instant

/**
 * A route for accessing status of features, external service connections, and general server information.
 * Examples of features that can be checked on are Email, Database, and Exception Reporting.
 */
inline fun <reified USER> ServerPath.healthCheck(noinline allowed: suspend (USER) -> Boolean = { true }): ApiEndpoint0<USER, Unit, ServerHealth> {
    return healthCheck(AuthInfo(), allowed)
}

fun <USER> ServerPath.healthCheck(
    authInfo: AuthInfo<USER>,
    allowed: suspend (USER) -> Boolean = { true },
): ApiEndpoint0<USER, Unit, ServerHealth> {
    return get.typed(
        authInfo = authInfo,
        inputType = Unit.serializer(),
        outputType = ServerHealth.serializer(),
        summary = "Get Server Health",
        description = "Gets the current status of the server",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit ->
            if (!allowed(user)) throw ForbiddenException()
            val now = Instant.now()
            serverHealth(
                features = Settings.requirements.mapValues { it.value() }.entries.mapNotNull {
                    val checkable =
                        it.value as? HealthCheckable ?: return@mapNotNull null
                    it.key to checkable
                }
                    .associate {
                        healthCache[it.second]?.takeIf {
                            now.toEpochMilli() - it.checkedAt.toEpochMilli() < 60_000 && it.level <= HealthStatus.Level.WARNING
                        }?.let { s -> return@associate it.first to s }
                        val result = withTimeoutOrNull(10_000L) { it.second.healthCheck() }
                            ?: HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Timed out after 10 seconds.")
                        healthCache[it.second] = result
                        it.first to result
                    }
            )
        }
    )
}

private fun serverHealth(
    features: Map<String, HealthStatus>,
): ServerHealth = ServerHealth(
    serverId = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")?.takeUnless { it.isEmpty() }
        ?: NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }
            .firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?",
    version = System.getenv("AWS_LAMBDA_FUNCTION_VERSION")?.takeUnless { it.isEmpty() } ?: "Unknown",
    memory = memory(),
    features = features,
    loadAverageCpu = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage,
)

private val healthCache = HashMap<HealthCheckable, HealthStatus>()

private fun memory(): ServerHealth.Memory = ServerHealth.Memory(
    max = Runtime.getRuntime().maxMemory(),
    total = Runtime.getRuntime().totalMemory(),
    free = Runtime.getRuntime().freeMemory(),
    systemAllocated = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
    usage = ((((Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
        .freeMemory()).toFloat() / Runtime.getRuntime().maxMemory().toFloat() * 100f) * 100).toInt()) / 100f,
)