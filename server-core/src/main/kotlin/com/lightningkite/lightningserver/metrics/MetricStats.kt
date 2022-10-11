package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@DatabaseModel
@Serializable
data class MetricStats(
    override val _id: String,
    val endpoint: String,
    val type: String,
    val timeString: String,
    val min: Double,
    val max: Double,
    val sum: Double,
    val count: Int,
): HasId<String> {
    val average: Double get() = sum / count.toDouble()
}

operator fun MetricStats.plus(other: MetricStats): MetricStats {
    return copy(
        min = min(this.min, other.min),
        max = max(this.max, other.max),
        sum = this.sum + other.sum,
        count = this.count + other.count
    )
}

fun MetricStats.asModification(): Modification<MetricStats> {
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
    timeString: String,
): MetricStats {
    val sum = this.sumOf { it.value }
    return MetricStats(
        _id = "$endpoint|$type|$timeString",
        endpoint = endpoint,
        type = type,
        timeString = timeString,
        min = this.minOf { it.value },
        max = this.maxOf { it.value },
        sum = sum,
        count = size
    )
}