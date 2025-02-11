@file:UseContextualSerialization(UUID::class, ServerFile::class)
package com.lightningkite.lightningserver.monitoring

import com.lightningkite.EmailAddress
import com.lightningkite.lightningdb.*
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.lightningserver.files.ServerFile
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.serverhealth.ServerHealth
import kotlin.time.Duration.Companion.minutes

@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: UUID = UUID.random(),
    val email: EmailAddress,
    val role: UserRole = UserRole.Developer,
) : HasId<UUID>

@Serializable
enum class UserRole {
    Developer, Admin
}

@Serializable
@GenerateDataClassPaths
data class Application(
    @MaxLength(128, 32) override val _id: String = "domain.com",
    val token: String? = null,
    val frontEnds: Set<String> = setOf(),
    val checkFrequencyMinutes: Int = 5,
    val currentVersion: String = "?",
    val slackChannel: String? = null,
    val slackUsers: Set<String> = setOf(),
    @Denormalized val reportToken: String? = null
): HasId<String>

@Serializable
@GenerateDataClassPaths
@AdminTableColumns(["application", "at", "overall"])
@IndexSet(["application", "at"])
data class ApplicationHealthCheck(
    override val _id: UUID = UUID.random(),
    @MaxLength(128, 32) @References(Application::class) val application: String,
    @Index val at: Instant = now(),
    val result: ServerHealth? = null,
    val overall: HealthStatus.Level? = result?.overall,
    val statusCode: Int = 0,
): HasId<UUID>

@Serializable
@GenerateDataClassPaths
data class ApplicationStackTrace(
    override val _id: UUID = UUID.random(),
    @MaxLength(128, 32) @References(Application::class) val application: String,
    val frontend: String? = null,
    val userAgent: String? = null,
    val context: String? = null,
    val auth: String? = null,
    val version: String,
    val trace: String,
    @Index val traceHash: Int = trace.hashCode(),
    val occurrences: Int = 1,
    val first: Instant = now(),
    val last: Instant = now(),
): HasId<UUID>

@Serializable
@GenerateDataClassPaths
data class Funnel(
    override val _id: String,
    @References(Funnel::class) val funnel: String,
): HasId<String>

@Serializable
@GenerateDataClassPaths
data class FunnelInstance(
    override val _id: UUID = UUID.random(),
    @References(Funnel::class) val funnel: String,
    @MaxLength(128, 32) @References(Application::class) val application: String = funnel.substringBefore("/"),
    val userAgent: String,
    val version: String,
    val errors: Set<String> = setOf(),
    val step: Int = 0,
    val success: Instant? = null,
    val started: Instant = now(),
    val expiry: Instant = now() + 20.minutes,
): HasId<UUID>

@Serializable
data class FunnelStart(
    @References(Funnel::class) val funnel: String,
    val userAgent: String,
    val version: String,
    val token: String,
    val expireAfterMinutes: Int = 20
)