package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.services.cache.setIfNotExists
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

internal class AwsAdapterSchedule(val root: AwsAdapter) {

    @Serializable
    data class Scheduled(val scheduled: String) : AwsLambdaInput

    /**
     * TTL of the per-tick lock. Matches [com.lightningkite.lightningserver.engine.local.LocalEngine]'s
     * default so scheduled-task behavior is consistent across engines. The lock is released in the
     * `finally` below as soon as the tick finishes, so this only matters as a backstop after a hard
     * crash (a Lambda killed mid-tick would otherwise leave the lock held until it expires).
     */
    private val scheduleLockTtl: Duration = 1.hours

    suspend fun handleSchedule(parsed: Scheduled): APIGatewayV2HTTPResponse {
        val p = PathSpec0.fromString(parsed.scheduled)
        val schedule = root.server.schedules[p]
            ?: return APIGatewayV2HTTPResponse(
                statusCode = 404,
                body = "No schedule '${parsed.scheduled}' found"
            )
        // EventBridge can fire a scheduled trigger more than once and to overlapping Lambda invocations.
        // Acquire a distributed lock (same key scheme and TTL as LocalEngine) so only one invocation runs
        // the tick; a losing invocation simply reports the same benign success and does no work. AWS
        // schedules are stateless per invocation, so the lock is the only coordination needed here — there
        // is no next-run persistence like the local engine keeps.
        val lockKey = "$p-lock"
        if (!root.cache.setIfNotExists(lockKey, true, scheduleLockTtl)) {
            return APIGatewayV2HTTPResponse(statusCode = 200)
        }
        try {
            with(root) {
                schedule.executeWithMetrics(p)
            }
            return APIGatewayV2HTTPResponse(statusCode = 200)
        } catch (e: Exception) {
            return APIGatewayV2HTTPResponse(statusCode = 500)
        } finally {
            // Always release the lock, even on cancellation (e.g. Lambda timeout), so a mid-tick failure
            // can't leave the lock stuck until its TTL expires. NonCancellable guards only this fast cleanup.
            withContext(NonCancellable) { root.cache.remove(lockKey) }
        }
    }
}
