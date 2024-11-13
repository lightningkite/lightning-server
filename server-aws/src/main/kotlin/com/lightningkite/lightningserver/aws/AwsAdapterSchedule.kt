package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.schedule.Scheduler
import kotlinx.serialization.Serializable

class AwsAdapterSchedule(val root: AwsAdapter) {

    @Serializable
    data class Scheduled(val scheduled: String)

    suspend fun handleSchedule(parsed: Scheduled): APIGatewayV2HTTPResponse {
        val schedule =
            Scheduler.schedules[parsed.scheduled]
                ?: return APIGatewayV2HTTPResponse(
                    statusCode = 404,
                    body = "No schedule '${parsed.scheduled}' found"
                )
        try {
            Metrics.handlerPerformance(schedule) {
                schedule.handler()
            }
            return APIGatewayV2HTTPResponse(statusCode = 200)
        } catch (e: Exception) {
            e.report(schedule)
            return APIGatewayV2HTTPResponse(statusCode = 500)
        }
    }
}