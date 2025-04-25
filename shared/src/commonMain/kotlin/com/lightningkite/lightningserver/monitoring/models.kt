@file:UseContextualSerialization(UUID::class, ServerFile::class)
package com.lightningkite.lightningserver.monitoring

import com.lightningkite.EmailAddress
import com.lightningkite.lightningserver.db.*
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.lightningserver.files.ServerFile
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.serverhealth.ServerHealth
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.minutes

@Serializable
data class FunnelStart(
    val funnel: String,
    val userAgent: String,
    val version: String,
    val expireAfterMinutes: Int = 20,
    val expectedErrorRate: Float = 0.05f
)
@Serializable
@GenerateDataClassPaths
data class FunnelSummary(
    override val _id: UUID = UUID.random(),
    val funnel: String,
    val date: LocalDate,
    val status: HealthStatus.Level,
    val success: Float = 0f,
    val successAfterError: Float = 0f,
    val error: Float = 0f,
    val abandoned: Float = 0f,
    val count: Int = 0,
): HasId<UUID>

@Serializable
@GenerateDataClassPaths
data class FunnelInstance(
    override val _id: UUID = UUID.random(),
    val funnel: String,
    val userAgent: String,
    val user: String? = null,
    val version: String,
    val errors: Set<String> = setOf(),
    val step: Int = 0,
    val success: Instant? = null,
    val started: Instant = now(),
    val expiry: Instant = now() + 20.minutes,
    val expectedErrorRate: Float = 0.05f
): HasId<UUID>