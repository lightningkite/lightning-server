@file:UseContextualSerialization(Instant::class, UUID::class)
package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.proof.ProofOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.security.SecureRandom
import java.time.Instant
import java.util.*


@GenerateDataClassPaths
@Serializable
data class OauthClient(
    override val _id: String,
    val scopes: Set<String>,
    val secretHash: String,
) : HasId<String> {

}

@Serializable
data class SubSessionRequest(
    val label: String,
    val scopes: Set<String>? = null,
    val oauthClient: String? = null,
    val expires: Instant? = null,
)

@GenerateDataClassPaths
@Serializable
data class Session<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    override val _id: UUID = UUID.randomUUID(),
    val secretHash: String,
    val derivedFrom: UUID? = null,
    val label: String? = null,
    val subjectId: ID,
    val createdAt: Instant = Instant.now(),
    val lastUsed: Instant = Instant.now(),
    val expires: Instant? = null,
    val terminated: Instant? = null,
    val ips: Set<String> = setOf(),
    val userAgents: Set<String> = setOf(),
    val scopes: Set<String>? = null,
    val oauthClient: String? = null,
) : HasId<UUID>


@Serializable
data class IdAndAuthMethods<ID>(
    val id: ID? = null,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val session: String? = null,
)