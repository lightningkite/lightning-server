
package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.UUID
import com.lightningkite.lightningdb.ExperimentalLightningServer
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.IndexSet
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration

@OptIn(ExperimentalLightningServer::class)
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
data class OtpSecret( //Version 5 rename to TOTPSecret
    override val _id: UUID = UUID.random(),
    val subjectType: String,
    val subjectId: String,
    val label: String,

    val secretBase32: String,
    val issuer: String,
    val period: Duration,
    val digits: Int,
    val algorithm: OtpHashAlgorithm,

    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<UUID>

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
data class PasswordSecret(
    override val _id: UUID = UUID.random(),
    val subjectType: String,
    val subjectId: String,

    val hash: String,
    val hint: String? = null,

    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<UUID>

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
data class KnownDeviceSecret(
    override val _id: UUID = UUID.random(),
    val subjectType: String,
    val subjectId: String,

    val hash: String,
    val deviceInfo: String,

    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<UUID>

@Serializable
data class EstablishPassword(
    val password: String,
    val hint: String? = null
)

@Serializable
data class EstablishOtp(
    val label: String? = null
)