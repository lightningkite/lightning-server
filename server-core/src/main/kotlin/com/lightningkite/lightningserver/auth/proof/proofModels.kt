@file:UseContextualSerialization(Instant::class)
package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningserver.encryption.SecureHasher
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import kotlinx.datetime.Instant
import java.util.*


private fun signingInfo(
    via: String,
    of: String,
    value: String,
    strength: Int = 1,
    at: Instant,
): ByteArray = ByteArrayOutputStream().use {
    DataOutputStream(it).use {
        it.writeUTF(via)
        it.writeUTF(of)
        it.writeUTF(value)
        it.writeInt(strength)
        it.writeLong(at.toEpochMilliseconds())
    }
    it.toByteArray()
}

fun SecureHasher.makeProof(
    info: ProofMethodInfo,
    value: String,
    at: Instant,
): Proof = Proof(
    via = info.via,
    of = info.of,
    strength = info.strength,
    value = value,
    at = at,
    signature = Base64.getEncoder().encodeToString(sign(signingInfo(info.via, info.of, value, info.strength, at)))
)

fun SecureHasher.verify(proof: Proof): Boolean {
    return verify(proof.run { signingInfo(via, of, value, strength, at) }, Base64.getDecoder().decode(proof.signature))
}

@Serializable
data class ProofEvidence(
    val value: String,
    val secret: String
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
    val value: String,
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