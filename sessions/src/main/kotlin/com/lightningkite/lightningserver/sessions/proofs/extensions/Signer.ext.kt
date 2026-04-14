package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.*
import kotlinx.io.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant

private fun signingInfo(
    via: String,
    property: String,
    value: String,
    strength: Int = 1,
    at: Instant,
    expiresAt: Instant? = null,
): ByteArray = Buffer()
    .apply {
        writeString(via)
        writeString(property)
        writeString(value)
        writeInt(strength)
        writeLong(at.toEpochMilliseconds())
        writeLong(
            expiresAt?.toEpochMilliseconds()
                ?: throw IllegalStateException("expiredAt cannot be null for proof signature verification")
        )
    }
    .readByteArray()

public suspend fun Signer.makeProof(
    info: ProofMethodInfo,
    property: String,
    value: String,
    at: Instant,
    expireAfter: Duration,
): Proof = Proof(
    via = info.via,
    property = property,
    strength = info.strength,
    value = value,
    at = at,
    expiresAt = at + expireAfter,
    signature = Base64.encode(
        sign(signingInfo(info.via, property, value, info.strength, at, at + expireAfter))
    )
)

public fun Signer.makeProofBlocking(
    info: ProofMethodInfo,
    property: String,
    value: String,
    at: Instant,
    expireAfter: Duration,
): Proof = Proof(
    via = info.via,
    property = property,
    strength = info.strength,
    value = value,
    at = at,
    expiresAt = at + expireAfter,
    signature = Base64.encode(
        signBlocking(signingInfo(info.via, property, value, info.strength, at, at + expireAfter))
    )
)

context(_: ServerRuntime, method: ProofMethod)
public suspend fun Signer.makeProof(
    property: String,
    value: String,
    info: ProofMethodInfo = method.info,
): Proof =
    makeProof(
        info,
        property,
        value,
        at = now(),
        expireAfter = method.proofExpiration
    )

public suspend fun Signer.verify(proof: Proof): Boolean =
    verify(
        proof.run { signingInfo(via, property, value, strength, at, expiresAt) },
        Base64.decode(proof.signature)
    )

public fun Signer.verifyBlocking(proof: Proof): Boolean =
    verifyBlocking(
        proof.run { signingInfo(via, property, value, strength, at, expiresAt) },
        Base64.decode(proof.signature)
    )