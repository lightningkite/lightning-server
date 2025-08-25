package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signBlocking
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.ProofMethodInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.time.Instant


private fun signingInfo(
    via: String,
    property: String,
    value: String,
    strength: Int = 1,
    at: Instant,
): ByteArray = ByteArrayOutputStream().use {
    DataOutputStream(it).use {
        it.writeUTF(via)
        it.writeUTF(property)
        it.writeUTF(value)
        it.writeInt(strength)
        it.writeLong(at.toEpochMilliseconds())
    }
    it.toByteArray()
}

public fun Signer.makeProof(
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
    signature = Base64.getEncoder()
        .encodeToString(signBlocking(signingInfo(info.via, property, value, info.strength, at)))
)
