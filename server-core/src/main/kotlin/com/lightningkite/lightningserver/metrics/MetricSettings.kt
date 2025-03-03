@file:UseContextualSerialization(Duration::class)

package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseContextualSerialization
import org.slf4j.LoggerFactory
import kotlin.time.Duration

@Serializable
data class MetricSettings(
    val url: String = "none",
    val trackingByEntryPoint: Set<String>? = null,
    val trackingTotalsOnly: Set<String>? = null,
) : () -> Metrics {
    fun trackedByEntryPoint(string: String): Boolean = (trackingByEntryPoint == null || string in trackingByEntryPoint)
    fun tracked(string: String): Boolean = (trackingByEntryPoint == null || string in trackingByEntryPoint) ||
            (trackingTotalsOnly == null || string in trackingTotalsOnly)

    companion object : Pluggable<MetricSettings, Metrics>() {
        init {
            register("none") {
                object : Metrics {
                    override val settings: MetricSettings = it
                    override suspend fun report(events: List<MetricEvent>) {}
                    override suspend fun healthCheck(): HealthStatus =
                        HealthStatus(HealthStatus.Level.OK, additionalMessage = "No metrics reporting")
                }
            }
            register("log") {
                object : Metrics {
                    override val settings: MetricSettings = it
                    val logger = LoggerFactory.getLogger("Metrics")
                    override suspend fun report(events: List<MetricEvent>) {
                        events.forEach {
                            logger.debug("Logging metric event $it")
                        }
                    }
                    override suspend fun healthCheck(): HealthStatus =
                        HealthStatus(HealthStatus.Level.OK, additionalMessage = "Metrics only recorded in log")
                }
            }
            register("db") {
                DatabaseMetrics(it) {
                    (Settings.requirements[it.url.substringAfter("://")]?.invoke() as? Database)
                        ?: DatabaseSettings(it.url.substringAfter("://")).invoke()
                }
            }
        }
    }

    override fun invoke(): Metrics = parse(url.substringBefore("://"), this)
}