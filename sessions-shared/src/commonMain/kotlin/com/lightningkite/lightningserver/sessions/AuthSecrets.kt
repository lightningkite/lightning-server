package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.sessions.proofs.TotpHashAlgorithm
import com.lightningkite.services.data.*
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class TotpSecret(
    override val _id: Uuid = Uuid.random(),
    val subjectType: String,
    val subjectId: String,
    val label: String,

    val secretBase32: String,
    val issuer: String,
    val period: Duration,
    val digits: Int,
    val algorithm: TotpHashAlgorithm,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<Uuid>

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class PasswordSecret(
    override val _id: Uuid = Uuid.random(),
    val subjectType: String,
    val subjectId: String,

    val hash: String,
    val hint: String? = null,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<Uuid>

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class KnownDeviceSecret(
    override val _id: Uuid = Uuid.random(),
    val subjectType: String,
    val subjectId: String,

    val hash: String,
    val deviceInfo: String,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<Uuid>

@Serializable
public data class EstablishPassword(
    val password: String,
    val hint: String? = null
)

@Serializable
public data class EstablishOtp(
    val label: String? = null
)