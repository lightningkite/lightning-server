package com.lightningkite.lightningserver.serverhealth

import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.typed.ApiEndpoint0
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.serializer
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
            ServerHealth(
                features = Settings.requirements.mapValues { it.value() }.entries.mapNotNull {
                    val checkable =
                        it.value as? HealthCheckable ?: return@mapNotNull null
                    it.key to checkable
                }
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
