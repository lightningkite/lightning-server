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
 * Request to create a sub-session with reduced privileges derived from an existing session.
 * Useful for creating temporary, scope-limited sessions for specific operations.
 *
 * @property label Human-readable label describing the purpose of this sub-session
 * @property scopes Set of scopes to grant to this sub-session. Defaults to root access but typically should be restricted
 * @property oauthClient Optional OAuth client ID if this sub-session is for OAuth purposes
 * @property expires Optional expiration time for the sub-session. If not provided, inherits from parent session
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
 * Represents an active authentication session for a subject (user).
 * Sessions are created after successful authentication and used to maintain stateful access.
 *
 * @param SUBJECT The type of entity this session represents (e.g., User)
 * @param ID The ID type of the subject
 * @property _id Unique session identifier
 * @property secretHash Hash of the session secret token (never store the raw secret)
 * @property derivedFrom ID of parent session if this is a sub-session
 * @property label Human-readable description of the session (e.g., "Web Browser", "Mobile App")
 * @property subjectId ID of the authenticated subject
 * @property createdAt When the session was created
 * @property lastUsed When the session was last used (updated on each request)
 * @property expires Optional hard expiration time for the session
 * @property stale Optional time when session should be considered stale and require re-authentication
 * @property terminated If set, the session has been explicitly terminated
 * @property ips Set of IP addresses that have used this session (for security auditing)
 * @property userAgents Set of user agent strings that have used this session (for security auditing)
 * @property scopes Authorization scopes granted to this session
 */
@GenerateDataClassPaths
@Serializable
@AdminTableColumns(["label", "subjectId", "scopes"])
@IndexSet(["subjectId", "terminated", "expires", "stale"])
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
    val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    // The OAuth/OpenID client this session was issued to, if it was created via the OpenID provider flow.
    // Not annotated with @References to avoid a circular module dependency on the provider module.
    val oauthClient: String? = null,
) : HasId<Uuid>

/**
 * Request to log in and create a new session.
 *
 * @property proofs List of authentication proofs to verify (e.g., password, OTP, email verification)
 * @property label Descriptive label for the session being created
 * @property scopes Authorization scopes to grant to the new session
 * @property expires Optional expiration time for the session
 */
@Serializable
public data class LogInRequest(
    val proofs: List<Proof>,
    val label: String = "Root Session",
    val scopes: Set<GrantedScope> = setOf(GrantedScope.root),
    val expires: Instant? = null,
)

/**
 * Response after checking proofs during login, containing the user ID and available authentication methods.
 *
 * @property id The authenticated subject's ID
 * @property options Available authentication methods/proofs that can be used
 * @property strengthRequired Minimum authentication strength required (sum of proof strengths)
 * @property refreshToken Token to use for refreshing or completing the login process
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
 * Result of checking authentication proofs before completing login.
 *
 * @property id The identified subject's ID
 * @property options Additional authentication options available
 * @property strengthRequired Minimum authentication strength still needed
 * @property readyToLogIn Whether provided proofs are sufficient to complete login
 * @property maxExpiration Maximum allowed expiration time based on the proofs provided
 */
@Serializable
public data class ProofsCheckResult<ID>(
    val id: ID,
    val options: List<ProofOption> = listOf(),
    val strengthRequired: Int = 1,
    val readyToLogIn: Boolean,
    val maxExpiration: Instant?,
)
