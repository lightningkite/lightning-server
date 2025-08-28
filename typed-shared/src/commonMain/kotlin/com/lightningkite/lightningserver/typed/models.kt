@file:UseContextualSerialization(Uuid::class, Instant::class, LocalDate::class)

package com.lightningkite.lightningserver.typed

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
public data class FunnelStart(
    val funnel: String,
    val userAgent: String,
    val version: String,
    val expireAfterMinutes: Int = 20,
    val expectedErrorRate: Float = 0.05f
)
@Serializable
@GenerateDataClassPaths
public data class FunnelSummary(
    override val _id: Uuid = Uuid.random(),
    val funnel: String,
    val date: LocalDate,
    val status: HealthStatus.Level,
    val success: Float = 0f,
    val successAfterError: Float = 0f,
    val error: Float = 0f,
    val abandoned: Float = 0f,
    val count: Int = 0,
): HasId<Uuid>

@Serializable
@GenerateDataClassPaths
public data class FunnelInstance(
    override val _id: Uuid = Uuid.random(),
    val funnel: String,
    val userAgent: String,
    val user: String? = null,
    val version: String,
    val errors: Set<String> = setOf(),
    val step: Int = 0,
    val success: Instant? = null,
    val started: Instant,
    val expiry: Instant,
    val expectedErrorRate: Float = 0.05f
): HasId<Uuid>


@Serializable
public data class ServerHealth(
    val serverId: String,
    val version: String,
    val memory: Memory,
    val features: Map<String, HealthStatus>,
    val loadAverageCpu: Double,
) {
    val overall: HealthStatus.Level get() = features.maxOf { it.value.level }
    public val loadAverageCpuHealth: HealthStatus
        get() = when (val amount = loadAverageCpu) {
            in 0.0..<0.7 -> HealthStatus(HealthStatus.Level.OK)
            in 0.7..<0.95 -> HealthStatus(
                HealthStatus.Level.WARNING,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )

            in 0.95..<1.0 -> HealthStatus(
                HealthStatus.Level.URGENT,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )

            else -> HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "CPU utilization: ${amount.times(100).roundToInt()}%"
            )
        }

    @Serializable
    public data class Memory(
        val max: Long,
        val total: Long,
        val free: Long,
        val systemAllocated: Long,
        val usage: Float,
    ) {
        public val status: HealthStatus
            get() = when (val amount = usage) {
                in 0f..<0.7f -> HealthStatus(HealthStatus.Level.OK)
                in 0.7f..<0.95f -> HealthStatus(
                    HealthStatus.Level.WARNING,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )

                in 0.95f..<1f -> HealthStatus(
                    HealthStatus.Level.URGENT,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )

                else -> HealthStatus(
                    HealthStatus.Level.ERROR,
                    additionalMessage = "Memory utilization: ${amount.times(100).roundToInt()}%"
                )
            }
    }
}