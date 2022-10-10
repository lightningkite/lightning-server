package com.lightningkite.lightningserver.metrics

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class MetricEvent(
    val type: String,
    val entryPoint: String,
    @Contextual val time: Instant,
    val value: Double
)