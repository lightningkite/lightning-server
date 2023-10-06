@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.auth.proof

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant

@Serializable
data class ProofEvidence(
    val key: String,
    val password: String
)

@Serializable
data class ProofMethodInfo(
    val via: String,
    val of: String,
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
    val of: String,
    val strength: Int = 1,
    val value: String,
    val at: Instant,
    val signature: String,
)