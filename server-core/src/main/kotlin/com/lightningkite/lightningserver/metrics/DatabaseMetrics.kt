package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.flow.toList
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DatabaseMetrics(val settings: MetricSettings, val database: ()-> Database): ServerPathGroup(ServerPath.root.path("metrics")), Metrics {
    init { prepareModels() }
    val collection by lazy { database().collection<MetricSpanStats>() }

    override suspend fun report(events: List<MetricEvent>) {
        for(span in settings.keepFor.keys) {
            events.groupBy { it.type to it.entryPoint }.forEach { (typeAndEntryPoint, typeEvents) ->
                val (type, entryPoint) = typeAndEntryPoint
                if (type in settings.trackingByEntryPoint) {
                    typeEvents.groupBy { it.time.roundTo(span) }.forEach { (rounded, spanEvents) ->
                        val stats = spanEvents.stats(entryPoint, type, rounded, span)
                        collection.upsertOneIgnoringResult(
                            condition { m -> m._id eq stats._id },
                            stats.asModification(),
                            stats
                        )
                    }
                }
            }
            events.groupBy { it.type }.forEach { (type, typeEvents) ->
                if (type in settings.trackingTotalsOnly || type in settings.trackingByEntryPoint) {
                    typeEvents.groupBy { it.time.roundTo(span) }.forEach { (rounded, spanEvents) ->
                        val stats = spanEvents.stats("total", type, rounded, span)
                        collection.upsertOneIgnoringResult(
                            condition { m -> m._id eq stats._id },
                            stats.asModification(),
                            stats
                        )
                    }
                }
            }
        }
    }

    val reportEndpoint = get.typed(
        summary = "Get Metrics",
        description = "Get the metrics for various statistics",
        errorCases = listOf(),
        implementation = { anon: Unit, input: Query<MetricSpanStats> ->
            return@typed collection.query(input).toList()
        }
    )

    val clearOldEndpoint = path("clear").get.typed(
        summary = "Clear Metrics",
        description = "Clear the metrics for various statistics",
        errorCases = listOf(),
        implementation = { anon: Unit, input: Unit ->
            settings.keepFor.entries.forEach { entry ->
                collection.deleteManyIgnoringOld(condition {
                    (it.timeSpan eq entry.key) and
                            (it.timeStamp lt Instant.now().minus(entry.value))
                })
            }
            "Cleared as requested"
        }
    )
    val clearOld = schedule("com.lightningkite.lightningserver.metrics.DatabaseMetrics.clearOld", Duration.ofHours(1)) {
        settings.keepFor.entries.forEach { entry ->
            collection.deleteManyIgnoringOld(condition {
                (it.timeSpan eq entry.key) and
                (it.timeStamp lt Instant.now().minus(entry.value))
            })
        }
    }
}
