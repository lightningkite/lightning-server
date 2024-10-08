@file:UseContextualSerialization(Instant::class, UUID::class)
package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningserver.auth.oauth.OauthClient
import com.lightningkite.lightningserver.auth.proof.ProofOption
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.lightningdb.AdminTableColumns
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.uuid

@Serializable
data class SubSessionRequest(
    val label: String,
    val scopes: Set<String> = setOf("*"),
    val oauthClient: String? = null,
    val expires: Instant? = null,
)

@GenerateDataClassPaths
@Serializable
@AdminTableColumns(["label", "subjectId", "scopes"])
data class Session<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    override val _id: UUID = uuid(),
    val secretHash: String,
    val derivedFrom: UUID? = null,
    val label: String? = null,
    val subjectId: ID,
    val createdAt: Instant = now(),
    val lastUsed: Instant = now(),
    val expires: Instant? = null,
    val terminated: Instant? = null,
    val ips: Set<String> = setOf(),
    val userAgents: Set<String> = setOf(),
    val scopes: Set<String>,
    @References(OauthClient::class) val oauthClient: String? = null,
) : HasId<UUID>


@Serializable
data class LogInRequest(
    val proofs: List<Proof>,
    val label: String = "Root Session",
    val scopes: Set<String> = setOf("*"),
    val expires: Instant? = null,
)

@Serializable
data class IdAndAuthMethods<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val session: String? = null,
)

@Serializable
data class ProofsCheckResult<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val readyToLogIn: Boolean,
    val maxExpiration: Instant?,
)
