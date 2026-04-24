package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.services.database.HasId
import kotlin.uuid.Uuid

/**
 * Client-side interface for authentication and session management endpoints.
 * Provides operations for logging in, managing sessions, and obtaining authentication tokens.
 *
 * @param USER The user type that implements HasId
 * @param ID The ID type for the user
 */
@LiveVersion(LiveAuthClientEndpoints::class)
public interface AuthClientEndpoints<USER : HasId<ID>, ID : Comparable<ID>> {
    /**
     * Legacy login endpoint. Prefer [logInV2] for new implementations.
     * Authenticates using a list of proofs and returns authentication methods.
     *
     * @param input List of authentication proofs (password, OTP, etc.)
     * @return User ID and available authentication options, plus optional refresh token
     */
    public suspend fun logIn(input: List<Proof>): IdAndAuthMethods<ID>

    /**
     * Login endpoint with full session configuration.
     * Authenticates and creates a new session with specified label, scopes, and expiration.
     *
     * @param input Login request with proofs and session configuration
     * @return User ID and available authentication options, plus optional refresh token
     */
    public suspend fun logInV2(input: LogInRequest): IdAndAuthMethods<ID>

    /**
     * Check if provided proofs are sufficient for login without creating a session.
     * Useful for progressive authentication and determining which additional proofs are needed.
     *
     * @param input List of authentication proofs to verify
     * @return Verification result indicating if ready to log in and what else is needed
     */
    public suspend fun checkProofs(input: List<Proof>): ProofsCheckResult<ID>

    /**
     * Exchange a refresh token for an access token.
     * Converts a long-lived refresh token into a short-lived access token.
     *
     * @param input Refresh token string
     * @return Access token (JWT or similar format)
     */
    public suspend fun getTokenSimple(input: String): String

    /**
     * Get the currently authenticated user's full profile.
     * Requires valid authentication.
     *
     * @return The authenticated user object
     */
    public suspend fun getSelf(): USER

    /**
     * Create a sub-session with reduced privileges from the current session.
     * Useful for delegating limited access or creating OAuth-like tokens.
     *
     * @param input Sub-session configuration (label, scopes, expiration)
     * @return Refresh token for the new sub-session
     */
    public suspend fun subsession(input: SubSessionRequest): String

    /**
     * Get authentication requirements for this application.
     * Returns available authentication methods and minimum strength required.
     *
     * @return Authentication requirements including available proof options
     */
    public suspend fun authRequirements(): AuthRequirements

    /**
     * Terminate the current session.
     * After calling this, the current access token will no longer be valid.
     */
    public suspend fun terminateSession()

    /**
     * Terminate a specific session by ID.
     * Only works if the session belongs to the currently authenticated user.
     *
     * @param sessionId UUID of the session to terminate
     */
    public suspend fun terminateSession(sessionId: Uuid)

//    public suspend fun openSession(input: String): String TODO: OAuth
//    public suspend fun getToken(input: OauthTokenRequest): OauthResponse
}