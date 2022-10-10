package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.modify
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.core.allServerEntryPoints
import com.lightningkite.lightningserver.typed.typed
import java.time.ZoneId

class CacheCircularMetrics(val settings: MetricSettings, val cache: ()->CacheInterface): ServerPathGroup(ServerPath.root.path("metrics")), Metrics {
    fun minuteKey(type: String, entryPoint: String, minute: Int) = "$type|$entryPoint|${minute}M"
    fun hourKey(type: String, entryPoint: String, hour: Int) = "$type|$entryPoint|${hour}H"
    override suspend fun report(events: List<MetricEvent>) {
        events.groupBy { it.type }.forEach { (type, events) ->
            if(type in settings.trackingByEntryPoint) {
                events.groupBy { minuteKey(it.type, it.entryPoint, it.time.atZone(ZoneId.systemDefault()).minute) }.forEach {
                    cache().modify<MetricStats>(it.key, maxTries = 10) { old ->
                        old?.let { old -> it.value.stats() + old } ?: it.value.stats()
                    }
                }
                events.groupBy { hourKey(it.type, it.entryPoint, it.time.atZone(ZoneId.systemDefault()).hour) }.forEach {
                    cache().modify<MetricStats>(it.key, maxTries = 10) { old ->
                        old?.let { old -> it.value.stats() + old } ?: it.value.stats()
                    }
                }
            }
            if(type in settings.trackingTotalsOnly || type in settings.trackingByEntryPoint) {
                events.groupBy { minuteKey(it.type, "total", it.time.atZone(ZoneId.systemDefault()).minute) }.forEach {
                    cache().modify<MetricStats>(it.key, maxTries = 10) { old ->
                        old?.let { old -> it.value.stats() + old } ?: it.value.stats()
                    }
                }
                events.groupBy { hourKey(it.type, "total", it.time.atZone(ZoneId.systemDefault()).hour) }.forEach {
                    cache().modify<MetricStats>(it.key, maxTries = 10) { old ->
                        old?.let { old -> it.value.stats() + old } ?: it.value.stats()
                    }
                }
            }
        }
    }

    val reportEndpoint = get.typed(
        summary = "Get Metrics",
        description = "Get the metrics for various statistics",
        errorCases = listOf(),
        implementation = { anon: Unit, input: Unit ->
            val out = LinkedHashMap<String, MetricStats>()
            val entries = allServerEntryPoints()
            val keyStarts = settings.trackingTotalsOnly.map { "$it|total" } +
                    (settings.trackingByEntryPoint + settings.trackingTotalsOnly).flatMap {
                        entries.map { e -> "$it|$e" }
                    }
            keyStarts.forEach { key ->
                (0..23).forEach { n ->
                    val k = "$key|${n}H"
                    try {
                        cache().get<MetricStats>(k)?.let { out[k] = it }
                    } catch(e: Exception) { /*squish*/ }
                }
                (0..59).forEach { n ->
                    val k = "$key|${n}M"
                    try {
                        cache().get<MetricStats>(k)?.let { out[k] = it }
                    } catch(e: Exception) { /*squish*/ }
                }
            }
            return@typed out
        }
    )
}
