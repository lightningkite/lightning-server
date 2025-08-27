package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.encryption.signBlocking
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.encryption.verifyBlocking
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.ProofMethodInfo
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.io.encoding.Base64
import kotlin.time.Instant

private fun signingInfo(
    via: String,
    property: String,
    value: String,
    strength: Int = 1,
    at: Instant,
): ByteArray = Buffer()
    .apply {
        writeString(via)
        writeString(property)
        writeString(value)
        writeInt(strength)
        writeLong(at.toEpochMilliseconds())
    }
    .readByteArray()

public suspend fun Signer.makeProof(
    info: ProofMethodInfo,
    property: String,
    value: String,
    at: Instant,
): Proof = Proof(
    via = info.via,
    property = property,
    strength = info.strength,
    value = value,
    at = at,
    signature = Base64.encode(
        sign(signingInfo(info.via, property, value, info.strength, at))
    )
)

public fun Signer.makeProofBlocking(
    info: ProofMethodInfo,
    property: String,
    value: String,
    at: Instant,
): Proof = Proof(
    via = info.via,
    property = property,
    strength = info.strength,
    value = value,
    at = at,
    signature = Base64.encode(
        signBlocking(signingInfo(info.via, property, value, info.strength, at))
    )
)

public suspend fun Signer.verify(proof: Proof): Boolean =
    verify(
        proof.run { signingInfo(via, property, value, strength, at) },
        Base64.decode(proof.signature)
    )

public fun Signer.verifyBlocking(proof: Proof): Boolean =
    verifyBlocking(
        proof.run { signingInfo(via, property, value, strength, at) },
        Base64.decode(proof.signature)
    )