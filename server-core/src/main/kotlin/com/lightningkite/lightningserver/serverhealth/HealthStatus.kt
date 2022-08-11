@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.serverhealth

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant

@Serializable
data class HealthStatus(val level: Level, val checkedAt: Instant = Instant.now(), val additionalMessage: String? = null) {
    @Serializable
    enum class Level(val color: String) {
        OK("green"),
        WARNING("yellow"),
        URGENT("orange"),
        ERROR("red")
    }
}