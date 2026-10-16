package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.services.database.HasId
import kotlin.uuid.Uuid

/**
 * Provides the client endpoints for logging in, managing sessions and obtaining authentication tokens.
 *
 * @param USER The type of user.
 * @param ID The type of the user's ID.
 */
@LiveVersion(LiveAuthClientEndpoints::class)
public interface AuthClientEndpoints<USER : HasId<ID>, ID : Comparable<ID>> {
    /**
     * Authenticates with the list of proofs [input] and returns the user ID, the available authentication options and, if the proofs were sufficient, a refresh token.
     *
     * Prefer [logInV2].
     */
    public suspend fun logIn(input: List<Proof>): IdAndAuthMethods<ID>

    /** Authenticates like [logIn] and creates a session with the label, scopes and expiration in [input]. */
    public suspend fun logInV2(input: LogInRequest): IdAndAuthMethods<ID>

    /** Returns whether the proofs [input] are sufficient to log in, and what else is needed, without creating a session. */
    public suspend fun checkProofs(input: List<Proof>): ProofsCheckResult<ID>

    /** Returns a short-lived access token in exchange for the long-lived refresh token [input]. */
    public suspend fun getTokenSimple(input: String): String

    /** Returns the authenticated user. */
    public suspend fun getSelf(): USER

    /** Returns a refresh token for a new sub-session of the current session, with the label, scopes and expiration in [input]. */
    public suspend fun subsession(input: SubSessionRequest): String

    /** Returns the available proof options and the minimum strength required. */
    public suspend fun authRequirements(): AuthRequirements

    /** Terminates the current session, after which its access token is no longer valid. */
    public suspend fun terminateSession()

    /** Terminates the session [sessionId], which must belong to the authenticated user. */
    public suspend fun terminateSession(sessionId: Uuid)

//    public suspend fun openSession(input: String): String TODO: OAuth
//    public suspend fun getToken(input: OauthTokenRequest): OauthResponse
}