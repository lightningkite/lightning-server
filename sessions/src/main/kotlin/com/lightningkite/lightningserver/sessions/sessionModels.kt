@file:UseContextualSerialization(Instant::class, Uuid::class)
package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.ProofOption
import com.lightningkite.services.data.AdminTableColumns
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
public data class SubSessionRequest(
    val label: String,
    val limitTo: RequestPredicates? = null,
    val forbid: RequestPredicates? = null,
    val oauthClient: String? = null,
    val expires: Instant? = null,
) {
    public constructor(
        label: String,
        scopes: Set<String>,
        oauthClient: String? = null,
        expires: Instant? = null
    ) : this(
        label,
        limitTo = if (scopes.isNotEmpty()) RequestPredicates(scopes = scopes) else null,
        forbid = null,
        oauthClient,
        expires
    )
}

@GenerateDataClassPaths
@Serializable
@AdminTableColumns(["label", "subjectId", "scopes"])
public data class Session<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    override val _id: Uuid = Uuid.random(),
    val secretHash: String,
    val derivedFrom: Uuid? = null,
    val label: String? = null,
    val subjectId: ID,
    val createdAt: Instant,
    val lastUsed: Instant,
    val expires: Instant? = null,
    val stale: Instant? = null,
    val terminated: Instant? = null,
    val ips: Set<String> = setOf(),
    val userAgents: Set<String> = setOf(),
    val limitTo: RequestPredicates? = null,
    val forbid: RequestPredicates? = null
//    @References(OauthClient::class) val oauthClient: String? = null,
) : HasId<Uuid> {
    public companion object : SerializableCache.Key<Uuid> {
        override val id: String = "session-id"
        override val serializer: KSerializer<Uuid> = Uuid.serializer()
    }
}


@Serializable
public data class LogInRequest(
    val proofs: List<Proof>,
    val label: String = "Root Session",
    val scopes: Set<String> = setOf("*"),
    val expires: Instant? = null,
)

@Serializable
public data class IdAndAuthMethods<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val refreshToken: String? = null,
)

@Serializable
public data class ProofsCheckResult<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val readyToLogIn: Boolean,
    val expires: Instant?,
)
