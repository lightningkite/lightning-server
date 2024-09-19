@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.auth.proof

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import kotlin.time.Duration

@Serializable
data class FinishProof(
    val key: String,
    val password: String
)

@Serializable
data class IdentificationAndPassword(
    val type: String,
    val property: String,
    val value: String,
    val password: String
)

@Serializable
data class ProofMethodInfo(
    val via: String,
    val property: String?,
    val strength: Int = 1,
)

@Serializable
data class ProofOption(
    val method: ProofMethodInfo,
    val value: String? = null,
)

@Serializable
data class Proof(
    val via: String,
    val strength: Int = 1,
    val property: String,
    val value: String,
    val at: Instant,
    val signature: String,
)

@Serializable
data class KnownDeviceOptions(
    val duration: Duration,
    val strength: Int
)

@Serializable
data class KnownDeviceSecretAndExpiration(
    val secret: String,
    val expiresAt: Instant
)