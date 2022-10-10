package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class MetricSettings(
    val url: String = "log",
    val trackingByEntryPoint: Set<String> = setOf("executionTime"),
    val trackingTotalsOnly: Set<String> = setOf(),
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
            register("cache-circular") {
                CacheCircularMetrics(it) { Settings.requirements[it.url.substringAfter("://")]!!() as CacheInterface }
            }
        }
    }

    override fun invoke(): Metrics = parse(url.substringBefore("://"), this)
}