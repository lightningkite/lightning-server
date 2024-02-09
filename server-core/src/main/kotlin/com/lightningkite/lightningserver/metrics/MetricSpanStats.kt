@file:UseContextualSerialization(Instant::class, Duration::class)

package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes

@GenerateDataClassPaths
@IndexSet(["type", "endpoint", "timeSpan", "timeStamp"])
@IndexSet(["timeSpan", "timeStamp"])
@Serializable
data class MetricSpanStats(
    override val _id: String,
    @Index val endpoint: String,
    @Index val type: String,
    val timeStamp: Instant = Instant.DISTANT_PAST,
    @Index val timeSpan: Duration = 1.minutes,
    val min: Double,
    val max: Double,
    val sum: Double,
    val count: Int,
) : HasId<String> {
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
        it.min coerceAtMost this@asModification.min
        it.max coerceAtLeast this@asModification.max
        it.sum += this@asModification.sum
        it.count += this@asModification.count
    }
}

fun List<MetricEvent>.stats(
    endpoint: String,
    type: String,
    timeStamp: Instant,
    timeSpan: Duration = 1.minutes,
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

fun Instant.roundTo(span: Duration): Instant =
    Instant.fromEpochMilliseconds(this.toEpochMilliseconds() / span.inWholeMilliseconds * span.inWholeMilliseconds)