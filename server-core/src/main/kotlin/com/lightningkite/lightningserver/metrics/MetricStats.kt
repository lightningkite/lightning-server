package com.lightningkite.lightningserver.metrics

import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class MetricStats(
    val min: Double,
    val average: Double,
    val max: Double,
    val sum: Double,
    val count: Int,
)

operator fun MetricStats.plus(other: MetricStats): MetricStats {
    return MetricStats(
        min = min(this.min, other.min),
        average = ((this.average * this.count) + (other.average * other.count)) / (this.count + other.count),
        max = min(this.max, other.max),
        sum = this.sum + other.sum,
        count = this.count + other.count
    )
}

fun List<MetricEvent>.stats(): MetricStats {
    val sum = this.sumOf { it.value }
    return MetricStats(
        min = this.minOf { it.value },
        average = sum / size,
        max = this.maxOf { it.value },
        sum = sum,
        count = size
    )
}