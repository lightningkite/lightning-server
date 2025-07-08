package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.discardRemaining
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LightningServerMonitorMetrics(
    override val settings: MetricSettings,
    val monitoringServerUrl: String,
    val applicationOverride: String? = null,
    val token: String
): Metrics {
    @Serializable
    data class ApplicationMetricSpanStatsReport(
        val application: String,
        val reportToken: String,
        val items: List<MetricSpanStats>
    )
    val spans = setOf(
        1.days,
        2.hours,
        10.minutes,
        1.minutes,
    )
    val application by lazy {
        applicationOverride ?: generalSettings().publicUrl.substringAfter("://").substringBefore('/')
    }
    override suspend fun report(events: List<MetricEvent>) {
        val detailed = spans.flatMap { span ->
            events
                .filter { it.entryPoint != null }
                .groupBy { it.metricType to it.entryPoint }
                .flatMap { (typeAndEntryPoint, typeEvents) ->
                    val (type, entryPoint) = typeAndEntryPoint
                    if (settings.trackedByEntryPoint(type.name)) {
                        typeEvents.groupBy { it.time.roundTo(span) }.map { (rounded, spanEvents) ->
                            spanEvents.stats(entryPoint!!, type.name, rounded, span)
                        }
                    } else listOf()
                }
        }
        val general = spans.flatMap { span ->
            events.groupBy { it.metricType }
                .flatMap { (type, typeEvents) ->
                    if (settings.tracked(type.name)) {
                        typeEvents.groupBy { it.time.roundTo(span) }.map { (rounded, spanEvents) ->
                            spanEvents.stats("total", type.name, rounded, span)
                        }
                    } else listOf()
                }
        }
        val assembledData = detailed + general
        if(assembledData.isEmpty()) return
        client.post("$monitoringServerUrl/applicationMetricSpanStats/accept") {
            contentType(io.ktor.http.ContentType.Application.Json)
            accept(io.ktor.http.ContentType.Application.Json)
            setBody(ApplicationMetricSpanStatsReport(application, token, assembledData))
        }.statusFailing().discardRemaining()
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            client.post("$monitoringServerUrl/applicationMetricSpanStats/accept") {
                contentType(io.ktor.http.ContentType.Application.Json)
                accept(io.ktor.http.ContentType.Application.Json)
                setBody(ApplicationMetricSpanStatsReport(application, token, listOf()))
            }.statusFailing().discardRemaining()
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}