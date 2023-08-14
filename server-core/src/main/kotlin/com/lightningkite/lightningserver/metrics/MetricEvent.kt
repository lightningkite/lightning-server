package com.lightningkite.lightningserver.metrics

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class MetricEvent(
    val metricType: MetricType,
    val entryPoint: String,
//    val otherDimensions: Map<String, String> = mapOf(),
    @Contextual val time: Instant,
    val value: Double
)