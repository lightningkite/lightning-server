@file:UseContextualSerialization(Instant::class, UUID::class)
package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.References
import com.lightningkite.lightningserver.auth.oauth.OauthClient
import com.lightningkite.lightningserver.auth.proof.ProofOption
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlinx.datetime.Instant
import com.lightningkite.UUID
import com.lightningkite.uuid
import java.util.*
import kotlin.time.Duration.Companion.minutes

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
    override val _id: UUID = uuid(),
    val secretHash: String,
    val derivedFrom: UUID? = null,
    val label: String? = null,
    val subjectId: ID,
    val createdAt: Instant = Clock.System.now(),
    val lastUsed: Instant = Clock.System.now(),
    val expires: Instant? = null,
    val terminated: Instant? = null,
    val ips: Set<String> = setOf(),
    val userAgents: Set<String> = setOf(),
    val scopes: Set<String>? = null,
    @References(OauthClient::class) val oauthClient: String? = null,
) : HasId<UUID>


@Serializable
data class IdAndAuthMethods<ID>(
    val id: ID? = null,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val session: String? = null,
)

@Serializable
data class FutureSession<ID>(
    val subjectId: ID,
    val scopes: Set<String>? = null,
    val expires: Instant = Clock.System.now().plus(5.minutes),
    val originalSessionId: UUID?,
    @References(OauthClient::class) val oauthClient: String? = null
)