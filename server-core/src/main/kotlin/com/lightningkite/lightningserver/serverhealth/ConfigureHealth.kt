package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.typed.ApiEndpoint0
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant

/**
 * A route for accessing status of features, external service connections, and general server information.
 * Examples of features that can be checked on are Email, Database, and Exception Reporting.
 *
 * @param path The path you wish the endpoint to be at.
 * @param features A list of `HealthCheckable` features that you want reports on.
 */
inline fun <reified USER> ServerPath.healthCheck(crossinline allowed: suspend (USER)->Boolean = { true }): ApiEndpoint0<USER, Unit, ServerHealth> {
    return get.typed(
        summary = "Get Server Health",
        description = "Gets the current status of the server",
        errorCases = listOf(),
        implementation = { user: USER, _: Unit ->
            if(!allowed(user)) throw ForbiddenException()
            val now = Instant.now()
            ServerHealth(
                features = Settings.current().entries.mapNotNull { it.key to (it.value as? HealthCheckable ?: return@mapNotNull null) }
                    .associate {
                        ServerHealth.healthCache[it.second]?.takeIf {
                            now.toEpochMilli() - it.checkedAt.toEpochMilli() < 60_000 && it.level <= HealthStatus.Level.WARNING
                        }?.let { s -> return@associate it.first to s }
                        val result = it.second.healthCheck()
                        ServerHealth.healthCache[it.second] = result
                        it.first to result
                    }
            )
        }
    )
}
