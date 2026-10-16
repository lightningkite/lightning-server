package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.ProofOption
import com.lightningkite.services.data.*
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Asks for a sub-session with reduced privileges derived from the current session.
 *
 * @property scopes The scopes to grant. The default, root, grants the parent session's scopes; anything else must be
 *   within them.
 * @property oauthClient The OAuth client ID, if the sub-session is for OAuth.
 * @property expires When the sub-session expires, inheriting the parent's expiration if `null`.
 */
@Serializable
public data class SubSessionRequest(
    val label: String,
    val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    val oauthClient: String? = null,
    val expires: Instant? = null,
) {
}

/**
 * Represents an authentication session of a subject, created after a successful login.
 *
 * @param SUBJECT The type of the subject.
 * @param ID The type of the subject's ID.
 * @property secretHash The hash of the session secret, never the raw secret.
 * @property derivedFrom The parent session, if this is a sub-session. Terminating the parent terminates this session.
 * @property authenticatedAt When the subject last proved their identity in this session's lineage, which sub-sessions
 *   inherit, so `maxAge` measures time since real authentication. `null`, for sessions stored before this field
 *   existed, means [createdAt].
 * @property lastUsed When the session was last used.
 * @property expires The hard expiration time, or `null` if there is none.
 * @property stale When the session goes stale and requires re-authentication, or `null` if never.
 * @property terminated When the session was explicitly terminated, or `null` if it was not.
 * @property ips The IP addresses that have used this session, for auditing.
 * @property userAgents The user agents that have used this session, for auditing.
 */
@Queryable
@AdminTableColumns(["label", "subjectId", "scopes"])
@IndexSet(["subjectId", "terminated", "expires", "stale"])
public data class Session<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    override val _id: Uuid = Uuid.random(),
    val secretHash: String,
    @Index val derivedFrom: Uuid? = null,
    val label: String? = null,
    val subjectId: ID,
    val createdAt: Instant,
    val authenticatedAt: Instant? = null,
    val lastUsed: Instant,
    val expires: Instant? = null,
    val stale: Instant? = null,
    val terminated: Instant? = null,
    val ips: Set<String> = setOf(),
    val userAgents: Set<String> = setOf(),
    val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
//    @References(OauthClient::class) val oauthClient: String? = null,
) : HasId<Uuid>

/**
 * Asks to log in with [proofs] and create a session.
 *
 * @property label A description of the session being created.
 * @property scopes The scopes to grant the session.
 * @property expires When the session expires, or `null` to use the default.
 */
@Serializable
public data class LogInRequest(
    val proofs: List<Proof>,
    val label: String = "Root Session",
    val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    val expires: Instant? = null,
)

/**
 * Describes the outcome of a login attempt.
 *
 * @property options The proof options still available.
 * @property strengthRequired The total proof strength required.
 * @property refreshToken The refresh token if the proofs were sufficient, and `null` otherwise.
 */
@Serializable
public data class IdAndAuthMethods<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val refreshToken: String? = null,
) {

    // Backwards compatibility, must serialize so cannot be a getter.
    @Deprecated(
        "Use refreshToken instead. This will be removed at a later date.",
        replaceWith = ReplaceWith("refreshToken")
    )
    val session: String? = refreshToken
}

/**
 * Describes whether a set of proofs is enough to log in, without having created a session.
 *
 * @property options The additional proof options available.
 * @property strengthRequired The proof strength still needed.
 * @property readyToLogIn Whether the proofs are sufficient to log in.
 * @property maxExpiration The latest expiration the session may have, based on the proofs.
 */
@Serializable
public data class ProofsCheckResult<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val readyToLogIn: Boolean,
    val maxExpiration: Instant?,
)
