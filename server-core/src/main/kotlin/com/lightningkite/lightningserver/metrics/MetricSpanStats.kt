@file:UseContextualSerialization(Instant::class, Duration::class)
package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

@DatabaseModel
@IndexSetJankPatch(["type", "endpoint", "timeSpan", "timeStamp", ":", "timeSpan", "timeStamp"])
//@IndexSet(["type", "endpoint", "timeSpan", "timeStamp"])
//@IndexSet(["timeSpan", "timeStamp"])
@Serializable
data class MetricSpanStats(
    override val _id: String,
    val endpoint: String,
    val type: String,
    val timeStamp: Instant = Instant.EPOCH,
    val timeSpan: Duration = Duration.ofMinutes(1),
    val min: Double,
    val max: Double,
    val sum: Double,
    val count: Int,
): HasId<String> {
    val average: Double get() = sum / count.toDouble()
}

operator fun MetricSpanStats.plus(other: MetricSpanStats): MetricSpanStats {
    return copy(
        min = min(this.min, other.min),
        max = max(this.max, other.max),
        sum = this.sum + other.sum,
        count = this.count + other.count
    )
}

fun MetricSpanStats.asModification(): Modification<MetricSpanStats> {
    return modification {
        Modification.Chain(listOf(
            it.min coerceAtMost this.min,
            it.max coerceAtLeast this.max,
            it.sum + this.sum,
            it.count + this.count
        ))
    }
}

fun List<MetricEvent>.stats(
    endpoint: String,
    type: String,
    timeStamp: Instant,
    timeSpan: Duration = Duration.ofMinutes(1),
): MetricSpanStats {
    val sum = this.sumOf { it.value }
    return MetricSpanStats(
        _id = "$endpoint|$type|$timeStamp|$timeSpan",
        endpoint = endpoint,
        type = type,
        timeStamp = timeStamp,
        timeSpan = timeSpan,
        min = this.minOf { it.value },
        max = this.maxOf { it.value },
        sum = sum,
        count = size
    )
}

fun Instant.roundTo(span: Duration): Instant = Instant.ofEpochMilli(this.toEpochMilli() / span.toMillis() * span.toMillis())