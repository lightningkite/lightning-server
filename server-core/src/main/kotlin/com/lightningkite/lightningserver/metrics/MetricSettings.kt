@file:UseContextualSerialization(Duration::class)
package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.slf4j.LoggerFactory
import java.time.Duration

@Serializable
data class MetricSettings(
    val url: String = "none",
    val trackingByEntryPoint: Set<String> = setOf("executionTime"),
    val trackingTotalsOnly: Set<String> = setOf(),
    val keepFor: Map<Duration, Duration> = mapOf(
        Duration.ofDays(1) to Duration.ofDays(7),
        Duration.ofHours(2) to Duration.ofDays(1),
        Duration.ofMinutes(10) to Duration.ofHours(2),
    )
) : () -> Metrics {
    companion object : Pluggable<MetricSettings, Metrics>() {
        init {
            register("none") {
                object : Metrics {
                    override suspend fun report(events: List<MetricEvent>) {}
                }
            }
            register("log") {
                object : Metrics {
                    val logger = LoggerFactory.getLogger("Metrics")
                    override suspend fun report(events: List<MetricEvent>) {
                        events.forEach {
                            logger.info("Logging metric event $it")
                        }
                    }
                }
            }
            register("db") {
                DatabaseMetrics(it) { Settings.requirements[it.url.substringAfter("://")]!!() as Database }
            }
        }
    }

    override fun invoke(): Metrics = parse(url.substringBefore("://"), this)
}