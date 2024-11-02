package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.UUID
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.now
import com.lightningkite.uuid
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
@GenerateDataClassPaths
data class PasskeyCredential(
    override val _id: UUID = uuid(),
    val subjectType: String,
    val subjectId: String,

    val publicKeyDerBase64: String,
    val algorithm: PublicKeyAlgorithm,

    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<UUID> {
}

enum class PublicKeyAlgorithm(val coseAlgorithmId: Int) {
    RS256(-257),
    ES256(-7),
    EdDSA(-8),
    PS256(-37)
}