@file:UseContextualSerialization(Duration::class)

package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.exceptions.ExceptionReporter
import com.lightningkite.lightningserver.exceptions.ExceptionSettings
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.services.Pluggable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseContextualSerialization
import org.slf4j.LoggerFactory
import kotlin.time.Duration

@Serializable
data class MetricSettings(
    val url: String = "none",
    val trackingByEntryPoint: Set<String> = setOf(Metrics.executionTime.name),
    val trackingTotalsOnly: Set<String> = setOf()
) : Metrics {
    @Transient
    val tracked = trackingTotalsOnly + trackingByEntryPoint

    companion object : Pluggable<MetricSettings, Metrics>() {
        init {
            register("none") {
                object : Metrics {
                    override val settings: MetricSettings = it
                    override suspend fun report(events: List<MetricEvent>) {}
                    override fun queue(event: MetricEvent) {}
                    override suspend fun healthCheck(): HealthStatus =
                        HealthStatus(HealthStatus.Level.OK, additionalMessage = "No metrics reporting")
                }
            }
            register("log") {
                object : Metrics {
                    override val settings: MetricSettings = it
                    val logger = LoggerFactory.getLogger("Metrics")
                    override fun queue(event: MetricEvent) {}
                    override suspend fun report(events: List<MetricEvent>) {
                        events.forEach {
                            logger.debug("Logging metric event $it")
                        }
                    }
                    override suspend fun healthCheck(): HealthStatus =
                        HealthStatus(HealthStatus.Level.OK, additionalMessage = "Metrics only recorded in log")
                }
            }
        }
    }

    private var backing: Metrics? = null
    val wraps: Metrics
        get() {
            if(backing == null) backing = parse(url.substringBefore("://"), this)
            return backing!!
        }

    override val settings: MetricSettings get() = wraps.settings
    override fun queue(event: MetricEvent) = wraps.queue(event)
    override suspend fun clean() = wraps.clean()
    override suspend fun healthCheck(): HealthStatus = wraps.healthCheck()
    override suspend fun report(events: List<MetricEvent>) = wraps.report(events)
}