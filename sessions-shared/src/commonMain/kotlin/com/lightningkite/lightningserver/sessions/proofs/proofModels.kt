package com.lightningkite.lightningserver.sessions.proofs

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant


@Serializable
public data class FinishProof(
    val key: String,
    val password: String
)

@Serializable
public data class IdentificationAndPassword(
    val type: String,
    val property: String,
    val value: String,
    val password: String
)


@Serializable
public data class Identification(
    val type: String,
    val property: String?,
    val value: String?,
)

@Serializable
public data class ProofMethodInfo(
    val via: String,
    val property: String?,
    val strength: Int = 1,
)

@Serializable
public data class ProofOption(
    val method: ProofMethodInfo,
    val value: String? = null,
)

@Serializable
public data class AuthRequirements(
    val options: List<ProofOption>,
    val strengthRequired: Int,
)

@Serializable
public data class Proof(
    val via: String,
    val strength: Int = 1,
    val property: String,
    val value: String,
    val at: Instant,
    val signature: String,
)

@Serializable
public data class KnownDeviceOptions(
    val duration: Duration,
    val strength: Int
)

@Serializable
public data class KnownDeviceSecretAndExpiration(
    val secret: String,
    val expiresAt: Instant
)