package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.cache.CacheInterface
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.modify
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.core.allServerEntryPoints
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.time.ZoneId

class DatabaseCircularMetrics(val settings: MetricSettings, val database: ()-> Database): ServerPathGroup(ServerPath.root.path("metrics")), Metrics {
    init { prepareModels() }
    val collection by lazy { database().collection<MetricStats>() }

    override suspend fun report(events: List<MetricEvent>) {
        events.groupBy { it.type }.forEach { (type, events) ->
            if(type in settings.trackingByEntryPoint) {
                events.groupBy { Triple(it.entryPoint, it.type, it.time.atZone(ZoneId.systemDefault()).minute) }.forEach {
                    val stats = it.value.stats(it.key.first, it.key.second, it.key.third.toString() + "M")
                    collection.upsertOneIgnoringResult(
                        condition { m -> m._id eq stats._id },
                        stats.asModification(),
                        stats
                    )
                }
                events.groupBy { Triple(it.entryPoint, it.type, it.time.atZone(ZoneId.systemDefault()).hour) }.forEach {
                    val stats = it.value.stats(it.key.first, it.key.second, it.key.third.toString() + "H")
                    collection.upsertOneIgnoringResult(
                        condition { m -> m._id eq stats._id },
                        stats.asModification(),
                        stats
                    )
                }
            }
            if(type in settings.trackingTotalsOnly || type in settings.trackingByEntryPoint) {
                events.groupBy { Pair(it.type, it.time.atZone(ZoneId.systemDefault()).minute) }.forEach {
                    val stats = it.value.stats("total", it.key.first, it.key.second.toString() + "M")
                    collection.upsertOneIgnoringResult(
                        condition { m -> m._id eq stats._id },
                        stats.asModification(),
                        stats
                    )
                }
                events.groupBy { Pair(it.type, it.time.atZone(ZoneId.systemDefault()).hour) }.forEach {
                    val stats = it.value.stats("total", it.key.first, it.key.second.toString() + "H")
                    collection.upsertOneIgnoringResult(
                        condition { m -> m._id eq stats._id },
                        stats.asModification(),
                        stats
                    )
                }
            }
        }
    }

    val reportEndpoint = get.typed(
        summary = "Get Metrics",
        description = "Get the metrics for various statistics",
        errorCases = listOf(),
        implementation = { anon: Unit, input: Query<MetricStats> ->
            return@typed collection.query(input).toList()
        }
    )
}
