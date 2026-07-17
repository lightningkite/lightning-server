package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import kotlinx.serialization.Serializable

internal class AwsAdapterSchedule(val root: AwsAdapter) {

    @Serializable
    data class Scheduled(val scheduled: String) : AwsLambdaInput

    suspend fun handleSchedule(parsed: Scheduled): APIGatewayV2HTTPResponse {
        val p = PathSpec0.fromString(parsed.scheduled)
        val schedule = root.server.schedules[p]
            ?: return APIGatewayV2HTTPResponse(
                statusCode = 404,
                body = "No schedule '${parsed.scheduled}' found"
            )
        try {
            with(root) {
                schedule.executeWithMetrics(p)
            }
            return APIGatewayV2HTTPResponse(statusCode = 200)
        } catch (e: Exception) {
            return APIGatewayV2HTTPResponse(statusCode = 500)
        }
    }
}