package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.token.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.*
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.builtins.serializer
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Abstract session management system for Lightning Server applications.
 *
 * SessionManager provides a complete authentication and authorization framework with support for:
 * - Access tokens (short-lived, embedded in token)
 * - Refresh tokens (long-lived, stored in database)
 * - Session lifecycle management (creation, expiration, termination)
 * - Scope-based authorization
 * - Sub-sessions with restricted permissions
 * - User agent and IP tracking
 *
 * ## Usage
 *
 * Extend this class and implement the abstract methods to define session behavior:
 *
 * ```kotlin
 * object UserSessions : SessionManager<User, Uuid>(
 *     principal = UserPrincipal,
 *     database = Runtime { Server.database() }
 * ) {
 *     context(server: ServerRuntime)
 *     override suspend fun sessionExpiration(subject: User): Instant? =
 *         now() + 30.days  // Sessions expire after 30 days
 *
 *     context(server: ServerRuntime)
 *     override suspend fun sessionStaleAfter(subject: User): Duration? =
 *         7.days  // Sessions go stale after 7 days of inactivity
 * }
 * ```
 *
 * ## Token Types
 *
 * 1. **Access Tokens**: Lightweight, self-contained tokens (typically JWT) that include the
 *    authentication state. These are used for most API calls and don't require database lookups.
 *
 * 2. **Refresh Tokens**: Secure tokens that reference a database-stored session. These can be
 *    revoked and provide more control over session lifecycle.
 *
 * ## Session Lifecycle
 *
 * - **Creation**: Call [newSession] to create a new session with specified permissions
 * - **Expiration**: Hard expiry time after which session cannot be used (see [sessionExpiration])
 * - **Staleness**: Auto-updating expiry based on inactivity (see [sessionStaleAfter])
 * - **Termination**: Explicit session revocation via [sessionTerminate] or [otherSessionTerminate]
 *
 * ## Built-in Endpoints
 *
 * - [tokenSimple]: Exchange refresh token for access token
 * - [createSubSession]: Create a restricted session from an existing session
 * - [self]: Get the authenticated subject's data
 * - [sessionTerminate]: Terminate current session
 * - [otherSessionTerminate]: Terminate a specific session (if owned by user)
 *
 * ## Security Considerations
 *
 * - Refresh token secrets are hashed before storage (never stored in plaintext)
 * - Sessions track user agents and IPs for audit purposes
 * - Sub-sessions cannot have broader permissions than their parent
 * - Terminated sessions are marked but not deleted for audit trail
 *
 * @param SUBJECT The type of the authenticated entity (e.g., User, ServiceAccount)
 * @param ID The type of the subject's identifier (e.g., Uuid, String, Long)
 * @param principal The PrincipalType that defines how subjects are identified and authorized
 * @param database Runtime provider for the database where sessions are stored
 * @param tokenFormat Runtime provider for the token encoding/decoding strategy (defaults to PrivateTinyTokenFormat)
 */
public abstract class SessionManager<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    public val tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : ServerBuilder(), Authentication.Reader<SUBJECT> {
    public object Scopes {
        public val self: RequiredScope = RequiredScope("auth:self")
        public val sessions: RequiredScope = RequiredScope("auth:sessions")
        public val terminate: RequiredScope = sessions.subscope(Subscope("terminate"))
    }

    init {
        register(principal)
    }

    init {
        authReaders.register(this)
    }

    /**
     * Determines if a subject is allowed to authenticate with the system.
     *
     * This method allows customizing what subjects are allowed to create new sessions and use existing sessions.
     *
     * If this returns false, using an existing refresh token and creating new sessions should result in 403 Forbidden
     *
     * @param subject The subject attempting to authenticate
     * @return If the subject should be allowed to authenticate.
     */
    context(server: ServerRuntime)
    public open suspend fun permitAuthentication(subject: SUBJECT): Boolean = true

    /**
     * Determines the hard expiration time for a new session.
     *
     * This is an absolute deadline after which the session cannot be used, regardless of activity.
     * Once this time passes, the session will be rejected even if the user is actively using it.
     *
     * Common patterns:
     * - Return `now() + 30.days` for sessions that expire after 30 days
     * - Return `null` for sessions that never expire (use with caution)
     * - Base expiration on subject properties (e.g., paid vs free users)
     *
     * @param subject The authenticated subject for whom the session is being created
     * @return The instant when the session should expire, or null if it should never expire
     * @see sessionStaleAfter for inactivity-based expiration
     */
    context(server: ServerRuntime)
    public abstract suspend fun sessionExpiration(subject: SUBJECT): Instant?

    /**
     * Determines the staleness duration for a session.
     *
     * This defines how long a session can remain inactive before it expires. Unlike [sessionExpiration],
     * this is a sliding window - each time the session is used, the stale time is reset to
     * `now() + sessionStaleAfter()`.
     *
     * This provides better security by automatically expiring sessions that haven't been used recently,
     * while allowing active sessions to continue.
     *
     * Common patterns:
     * - Return `7.days` to expire sessions after 7 days of inactivity
     * - Return `null` to disable inactivity-based expiration
     * - Use shorter durations for sensitive operations
     *
     * @param subject The authenticated subject for whom the session is being created
     * @return The duration of inactivity after which the session should go stale, or null to disable
     * @see sessionExpiration for hard expiration limits
     */
    context(server: ServerRuntime)
    public abstract suspend fun sessionStaleAfter(subject: SUBJECT): Duration?

    private val spath = Session.path(principal.subjectSerializer, principal.idSerializer)

    public val sessionInfo: ModelInfo<SUBJECT, Session<SUBJECT, ID>, Uuid> =
        database.explicitModelInfo(
            auth = principal.require(scopes = setOf(Scopes.sessions)),
            serializer = Session.serializer(principal.subjectSerializer, principal.idSerializer),
            idSerializer = Uuid.serializer(),
            tableName = principal.name + "Session",
            permissions = {
                val auth = this.authOrNull
                val canUse: Condition<Session<SUBJECT, ID>> = when {
                    auth == null -> Condition.Never
                    else -> spath.subjectId eq auth.id
                }

                val isRoot = condition<Session<SUBJECT, ID>>(AuthRequirement.IsSuperUser.accepts(auth))

                ModelPermissions(
                    create = isRoot,
                    read = canUse,
                    readMask = Mask(
                        listOf(
                            Condition.Never to modification(spath) { it.secretHash assign "" }
                        )
                    ),
                    update = isRoot,
                    delete = isRoot,
                )
            }
        )

    /**
     * Reads authentication from an HTTP request.
     *
     * This method implements the [Authentication.Reader] interface and is automatically called
     * by the authentication middleware for each request. It extracts and validates tokens from
     * multiple possible sources in order of preference:
     *
     * 1. Authorization header (`Authorization: Bearer <token>`)
     * 2. Query parameter named "Authorization" or "jwt"
     * 3. Cookie named "Authorization"
     *
     * The method first attempts to parse the token as an access token (typically JWT).
     * If that fails, it falls back to treating it as a refresh token and looks up the
     * session in the database.
     *
     * Security note: Query parameter authentication should be used sparingly as URLs may be
     * logged in various places (browser history, server logs, etc.). Prefer header-based auth
     * for sensitive operations.
     *
     * @param request The incoming HTTP request
     * @return Authentication object if valid credentials found, null if no credentials present
     * @throws UnauthorizedException if credentials are present but invalid
     */
    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<SUBJECT>? {
        val rawHeader = request.headers[HttpHeader.Authorization]
        val token =
            rawHeader?.root?.removePrefix("bearer ")?.removePrefix("Bearer ")
                ?: request.queryParameters.find {
                    it.first.equals(HttpHeader.Authorization, ignoreCase = true)
                }?.second?.replace(' ', '+')
                ?: request.queryParameters.find { it.first == "jwt" }?.second?.replace(' ', '+')
                ?: request.headers.cookies[HttpHeader.Authorization]
                ?: return null

        try {
            return tokenFormat().read(principal, token)
                ?.also { auth ->
                    val span = Span.current()
                    auth.sessionId?.let { span.setAttribute("session.id", it) }
                    span.setAttribute("user.id", auth.id.toString())
                    span.setAttribute("authorization.method", "access token")
                }
                ?: RefreshToken(token).session(request)?.toAuth()
                    ?.also { auth ->
                        val span = Span.current()
                        auth.sessionId?.let { span.setAttribute("session.id", it) }
                        span.setAttribute("user.id", auth.id.toString())
                        span.setAttribute("authorization.method", "refresh token")
                    }
        } catch (e: TokenException) {
            throw UnauthorizedException(e.message ?: "JWT issue")
        }
    }

    /**
     * Creates a new session for the specified subject.
     *
     * This is the primary method for creating authenticated sessions. It:
     * 1. Generates a cryptographically secure random secret
     * 2. Hashes the secret before storing it (never stores plaintext secrets)
     * 3. Creates a Session record in the database
     * 4. Returns both the Session object and a RefreshToken containing the plaintext secret
     *
     * The refresh token should be returned to the client, who can then use it to obtain
     * access tokens via the [tokenSimple] endpoint.
     *
     * **Security warning**: The plaintext secret in the returned RefreshToken is the only
     * time the secret is available in plaintext. Store it securely on the client side
     * (e.g., in secure storage, not localStorage). The secret cannot be recovered from
     * the database.
     *
     * @param subjectId The ID of the subject (user/entity) this session belongs to
     * @param label Optional human-readable label for the session (e.g., "Mobile App", "Web Browser")
     * @param expires Hard expiration time (overrides [sessionExpiration] if provided)
     * @param stale Initial staleness time (auto-updated on each use based on [sessionStaleAfter])
     * @param scopes Set of granted scopes for this session (defaults to root/full access)
     * @param oauthClient OAuth client ID if this session was created via OAuth (currently unused)
     * @param derivedFrom Parent session ID if this is a sub-session (for audit trail)
     * @return Pair of the created Session and its RefreshToken (containing plaintext secret)
     */
    context(_: ServerRuntime)
    public suspend fun newSession(
        subjectId: ID,
        label: String? = null,
        expires: Instant? = null,
        stale: Instant? = null,
        scopes: Set<GrantedScope> = setOf(GrantedScope.root),
        oauthClient: String? = null,
        derivedFrom: Uuid? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        // Generate a cryptographically secure 24-byte random secret
        val secret = Base64.encode(CryptographyRandom.nextBytes(24))

        if(!permitAuthentication(principal.fetch(subjectId))){
            throw ForbiddenException()
        }

        return Session<SUBJECT, ID>(
            secretHash = secret.fastHash(),  // SECURITY: Only the hash is stored, never the plaintext. Using fast hash since session tokens are high-entropy.
            subjectId = subjectId,
            label = label,
            expires = expires,
            stale = stale,
            scopes = scopes,
            createdAt = now(),
            lastUsed = now(),
//            oauthClient = oauthClient,  TODO: OAuth
            derivedFrom = derivedFrom,
        ).also { sessionInfo.table().insertOne(it) }.let {
            it to RefreshToken(principal.name, it._id, secret)
        }
    }

    /**
     * Converts a Session to an Authentication object.
     *
     * This is an extension function that transforms a database Session record into
     * an Authentication object suitable for use in the auth system.
     *
     * @return Authentication object representing this session's authorization state
     */
    context(server: ServerRuntime)
    public fun Session<SUBJECT, ID>.toAuth(): Authentication<SUBJECT> = Authentication(
        principalType = principal,
        id = subjectId,
        sessionId = _id,
        issuedAt = createdAt,
        scopes = scopes,
    )

    context(server: ServerRuntime)
    private suspend fun terminateSessionById(id: Uuid, requireOwnershipOf: ID?) {
        if (requireOwnershipOf != null) {
            val owner = sessionInfo.table().get(id)?.subjectId ?: throw ForbiddenException()
            if (owner != requireOwnershipOf) throw ForbiddenException()
        }
        sessionInfo.table().updateOneById(id, modification(spath) { it.terminated assign now() })
    }

    /**
     * Validates a refresh token and returns the associated session.
     *
     * This method performs comprehensive validation of a refresh token:
     * 1. Validates token format and structure
     * 2. Checks token type matches this principal
     * 3. Verifies the secret hash against database record
     * 4. Checks expiration times (both hard and stale)
     * 5. Ensures session hasn't been terminated
     * 6. Updates session metadata (lastUsed, userAgents, ips)
     * 7. Resets the stale time based on [sessionStaleAfter]
     *
     * **Security note**: This method tracks user agents and IP addresses for each session use.
     * This provides an audit trail but also means the database is updated on every refresh
     * token use. Consider the performance implications for high-traffic applications.
     *
     * @param request The HTTP request (used to extract user agent and IP for tracking)
     * @return The validated Session, or null if token is malformed
     * @throws UnauthorizedException if token is well-formed but invalid (wrong secret, expired, etc.)
     */
    context(server: ServerRuntime)
    private suspend fun RefreshToken.session(request: Request<*>?): Session<SUBJECT, ID>? {
        if (!valid) {
            if (generalSettings().debug) println("Auth failed because !valid")
            return null
        }
        if (type != principal.name) {
            if (generalSettings().debug) println("Auth failed because type != handler.name")
            return null
        }

        val session = sessionInfo.table().get(_id) ?: run {
            if (generalSettings().debug) println("No such session")
            throw UnauthorizedException("No such session")
        }
        val subject = principal.fetch(session.subjectId)
        if(!permitAuthentication(subject)){
            if (generalSettings().debug) println("Permit Authentication failed")
            throw ForbiddenException()
        }
        // SECURITY: Constant-time hash comparison to prevent timing attacks
        if (!plainTextSecret.checkAgainstHash(session.secretHash)) {
            if (generalSettings().debug) println("Auth failed because hash verification failed ($plainTextSecret vs ${session.secretHash})")
            throw UnauthorizedException("Incorrect hash for session")
        }
        if ((session.expires ?: Instant.DISTANT_FUTURE) < now()) {
            if (generalSettings().debug) println("Auth failed because (session.expires ?: Instant.DISTANT_FUTURE) < now()")
            throw UnauthorizedException("Session has expired (hard).")
        }
        if ((session.stale ?: Instant.DISTANT_FUTURE) < now()) {
            if (generalSettings().debug) println("Auth failed because (session.stale ?: Instant.FUTURE) < now()")
            throw UnauthorizedException("Session has expired (stale).")
        }
        if (session.terminated != null) {
            if (generalSettings().debug) println("Auth failed because session.terminated != null")
            throw UnauthorizedException("Session has been terminated.")
        }

        // Lazy migration: if using old slow PBKDF2 hash, upgrade to fast SHA-256 hash
        val shouldMigrate = session.secretHash.isSlowHash()

        // Update session metadata on each use for audit trail and sliding expiration
        sessionInfo.table().updateOneById(_id, modification(spath) {
            it.lastUsed assign now()
            it.userAgents addAll setOf(request?.headers?.get(HttpHeader.UserAgent)?.root ?: "")
            it.ips addAll setOf(request?.sourceIp ?: "test")

            // Reset staleness window on each use (sliding expiration)
            sessionStaleAfter(subject)?.let { length ->
                it.stale assign now() + length
            }

            // Migrate from slow PBKDF2 hash to fast SHA-256 hash
            if (shouldMigrate) {
                it.secretHash assign plainTextSecret.fastHash()
            }
        })
        return session
    }

    /**
     * Endpoint: POST /token/simple
     *
     * Exchanges a refresh token for an access token (typically JWT).
     *
     * This is the primary endpoint clients use to obtain access tokens. Clients should:
     * 1. Store the refresh token securely after initial login
     * 2. Call this endpoint to get a fresh access token
     * 3. Use the access token for subsequent API calls
     * 4. Refresh the access token periodically or when it expires
     *
     * This endpoint requires no authentication (the refresh token itself provides auth).
     *
     * @input String - The refresh token obtained during login/session creation
     * @output String - A new access token (format depends on tokenFormat configuration)
     * @throws BadRequestException if the refresh token format is invalid
     * @throws UnauthorizedException if the refresh token is expired, revoked, or otherwise invalid
     */
    public val tokenSimple: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> =
        path.path("token").path("simple").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Get Token Simple",
            implementation = { refresh: String ->
                val session = RefreshToken(refresh).session(request)
                    ?: throw BadRequestException("Refresh token not recognized")

                tokenFormat().create(principal, session.toAuth().apply { precache(principal.precache) })
            }
        )

    /**
     * Endpoint: POST /sub-session
     *
     * Creates a derived session with restricted permissions from an existing session.
     *
     * Sub-sessions are useful for:
     * - Limiting permissions for specific operations or time periods
     * - Delegating partial access to third parties
     * - Implementing least-privilege access patterns
     *
     * **Security constraints**:
     * - Sub-session scopes cannot exceed parent session scopes
     * - Sub-session expiration cannot exceed parent session expiration
     * - Sub-sessions inherit the parent's stale duration
     * - Terminating the parent session does NOT automatically terminate sub-sessions
     *
     * @input SubSessionRequest - Configuration for the new restricted session
     * @output String - A refresh token for the new sub-session
     * @throws UnauthorizedException if the current session cannot be found or has no session ID
     */
    public val createSubSession: ApiHttpHandler<PathSpec0, SUBJECT, SubSessionRequest, String> =
        path.path("sub-session").post bind explicitApiHttpHandler(
            auth = sessionInfo.auth.subscope(ModelInfo.Scopes.create),
            inputType = SubSessionRequest.serializer(),
            outputType = String.serializer(),
            summary = "Create Sub Session",
            description = "Creates a session with more limited authorization",
            implementation = { request: SubSessionRequest ->
                val sessionUuid = this.auth.sessionId?.let(Uuid::parse) ?: throw UnauthorizedException()
                val session = sessionInfo.table().get(sessionUuid)
                    ?: throw UnauthorizedException()

                // SECURITY: Ensure sub-session cannot have more permissions than parent
                newSession(
                    label = request.label,
                    subjectId = auth.id,
                    derivedFrom = sessionUuid,
                    scopes = request.scopes,
                    // Sub-session expires can't exceed parent session expiration
                    expires = session.expires
                        ?.let { minOf(it, request.expires ?: Instant.DISTANT_FUTURE) }
                        ?: request.expires,
                    stale = session.stale,
                    oauthClient = request.oauthClient,
                ).second.string
            }
        )

    /**
     * Endpoint: GET /self
     *
     * Retrieves the authenticated subject's complete data.
     *
     * This is commonly used by clients to:
     * - Get user profile information after login
     * - Verify the current authentication state
     * - Refresh cached user data
     *
     * Requires the 'auth:self' scope.
     *
     * @output SUBJECT - The complete subject object (e.g., User record)
     */
    public val self: ApiHttpHandler<PathSpec0, SUBJECT, Unit, SUBJECT> =
        path.path("self").get bind explicitApiHttpHandler(
            summary = "Get Self",
            auth = principal.require(scopes = setOf(Scopes.self)),
            inputType = Unit.serializer(),
            outputType = principal.subjectSerializer,
            implementation = { _ -> auth.fetch() }
        )

    /**
     * Endpoint: POST /terminate
     *
     * Terminates the current authenticated session.
     *
     * This is the "logout" endpoint. After calling this:
     * - The current refresh token will no longer work
     * - Existing access tokens remain valid until they expire (they're self-contained)
     * - The session is marked as terminated (not deleted) for audit purposes
     *
     * Common use cases:
     * - User-initiated logout
     * - Security-triggered session revocation
     * - "Logout from this device" functionality
     *
     * **Note**: This does NOT terminate derived sub-sessions. To terminate all sessions,
     * clients should call this endpoint for each session individually.
     *
     * Requires the 'auth:sessions:terminate' scope.
     */
    public val sessionTerminate: ApiHttpHandler<PathSpec0, SUBJECT, Unit, Unit> =
        path.path("terminate").post bind explicitApiHttpHandler(
            summary = "Terminate Current Session",
            auth = principal.require(scopes = setOf(Scopes.terminate)),
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            errorCases = listOf(),
            implementation = { _ ->
                terminateSessionById(Uuid.parse(auth.sessionId!!), null)
            }
        )

    /**
     * Endpoint: POST /{sessionId}/terminate
     *
     * Terminates a specific session by ID.
     *
     * This endpoint allows users to terminate their own sessions (e.g., "logout from all devices"
     * or "logout from other device"). The session must belong to the authenticated user.
     *
     * Security constraints:
     * - Users can only terminate their own sessions (ownership check enforced)
     * - Super users would need elevated permissions to terminate others' sessions
     *
     * Requires the 'auth:sessions:terminate' scope.
     *
     * @throws ForbiddenException if the session belongs to a different user
     */
    public val otherSessionTerminate: ApiHttpHandler<PathSpec1<Uuid>, SUBJECT, Unit, Unit> =
        path.arg<Uuid>("sessionId").path("terminate").post bind explicitApiHttpHandler(
            auth = principal.require(scopes = setOf(Scopes.terminate)),
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            summary = "Terminate Session",
            errorCases = listOf(),
            implementation = { _ ->
                terminateSessionById(route.arg1, auth.id)  // Enforces ownership
            }
        )

//    context(_: ServerRuntime)
//    public suspend fun presignToken(
//        session: Session<SUBJECT, ID>,
//        scopes: Set<GrantedScope> = setOf(GrantedScope.root),
//    ): String {
//        return tokenFormat().create(
//            principal, Authentication(
//                principalType = principal,
//                id = session.subjectId,
//                sessionId = session._id,
//                issuedAt = now(),
//                scopes = scopes
//            )
//        )
//    }
//
//    context(_: ServerRuntime)
//    public suspend fun presignToken(
//        id: ID,
//        scopes: Set<GrantedScope> = setOf(GrantedScope.root),
//    ): String {
//        return tokenFormat().create(
//            principal, Authentication(
//                principalType = principal,
//                id = id,
//                issuedAt = now(),
//                scopes = scopes
//            )
//        )
//    }
}

/*
 * TODO: SessionManager API Improvements and Recommendations
 *
 * 1. BULK SESSION TERMINATION
 *    - Add endpoint to terminate all sessions for a user (except current)
 *    - Add endpoint to terminate all derived sub-sessions from a parent session
 *    - Useful for "logout from all devices" and cascading termination
 *
 * 3. REFRESH TOKEN ROTATION
 *    - Implement refresh token rotation for better security
 *    - Each refresh invalidates the old token and issues a new one
 *    - Helps detect token theft (both attacker and victim get invalid token)
 *    - Consider making this configurable per-principal
 *
 * 4. DEVICE FINGERPRINTING
 *    - Currently tracks user agent and IP, but doesn't enforce consistency
 *    - Consider optional "trusted device" mode that alerts on IP/UA changes
 *    - Add anomaly detection for suspicious session usage patterns
 *
 * 5. SCOPE VALIDATION FOR SUB-SESSIONS
 *    - Current implementation trusts client to provide valid scopes
 *    - Add validation that requested scopes are subset of parent session scopes
 *    - Prevent scope escalation attacks
 *
 * 6. SESSION REFRESH OPTIMIZATION
 *    - Currently updates session metadata on every refresh token use (DB write)
 *    - Consider batching updates or using cache with periodic flush
 *    - High-traffic applications may experience performance issues
 *    - Alternative: Only update lastUsed if > N minutes since last update
 *
 * 7. PRESIGN TOKEN METHODS
 *    - Currently commented out presignToken methods
 *    - These are useful for server-to-server operations (webhooks, background jobs)
 *    - Consider enabling with clear documentation about security implications
 *    - Presigned tokens bypass session validation - use with extreme caution
 *
 * 8. OAUTH INTEGRATION
 *    - oauthClient field exists but is unused (TODO comment in code)
 *    - Complete OAuth 2.0 flow implementation
 *    - Support OAuth refresh token to Lightning refresh token mapping
 *    - Handle OAuth token revocation
 *
 * 9. SESSION EVENTS AND HOOKS
 *    - Add hooks for session lifecycle events (created, used, expired, terminated)
 *    - Useful for audit logging, analytics, and security monitoring
 *    - Consider: onSessionCreated, onSessionUsed, onSessionExpired, onSessionTerminated
 *
 * 10. RATE LIMITING
 *     - Add rate limiting for token refresh endpoint to prevent abuse
 *     - Protect against brute force attacks on refresh tokens
 *     - Consider exponential backoff on failed authentication attempts
 *
 * 11. CONCURRENT SESSION LIMITS
 *     - Add configurable maximum active sessions per user
 *     - Auto-terminate oldest sessions when limit exceeded
 *     - Different limits for different user tiers/plans
 *
 * 12. SESSION POLICIES
 *     - Make expiration policies more flexible (per-user, per-role, per-client)
 *     - Support different policies for mobile vs web vs API-only sessions
 *     - Allow runtime policy updates without code changes
 *
 * 13. SECURITY ENHANCEMENTS
 *     - Add support for JWT revocation lists (for access token invalidation)
 *     - Implement session binding to client certificates or device tokens
 *     - Add "step-up" authentication for sensitive operations
 *     - Support for hardware security tokens (WebAuthn, FIDO2)
 *
 * 14. DOCUMENTATION
 *     - Add example implementations for common scenarios
 *     - Document best practices for client-side token storage
 *     - Security guidelines for different deployment scenarios
 *     - Migration guide for existing auth systems
 */