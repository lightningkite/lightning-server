package com.lightningkite.lightningserver.sessions

import kotlinx.coroutines.flow.toList
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
import com.lightningkite.lightningserver.runtime.serverRuntime
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
 * Manages sessions of [SUBJECT] with short-lived access tokens backed by long-lived refresh tokens stored in the database.
 *
 * Extend this class and implement [sessionExpiration] and [sessionStaleAfter]:
 *
 * ```kotlin
 * object UserSessions : SessionManager<User, Uuid>(
 *     principal = UserPrincipal,
 *     database = Runtime { Server.database() }
 * ) {
 *     context(server: ServerRuntime)
 *     override suspend fun sessionExpiration(subject: User): Instant? = now() + 30.days
 *
 *     context(server: ServerRuntime)
 *     override suspend fun sessionStaleAfter(subject: User): Duration? = 7.days
 * }
 * ```
 *
 * Refresh token secrets are hashed before storage, and terminated sessions are marked rather than deleted. A sub-session never has broader scopes or a later expiration than its parent.
 *
 * @param principal Defines how subjects are identified and authorized.
 * @param database Provides the database where sessions are stored.
 * @param tokenFormat Provides the token encoding strategy, [PrivateTinyTokenFormat] by default.
 */
public abstract class SessionManager<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    public val tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
    /**
     * Decorates the session table, so a deployment can observe session creation, update and termination.
     *
     * The decorator is applied ahead of permissions, so it sees privileged and unprivileged access alike, and it sees reads as well as writes. To observe writes only, use `sessionInfo.registerChangeListener`.
     */
    private val sessionSignals: context(ServerRuntime) (Table<Session<SUBJECT, ID>>) -> Table<Session<SUBJECT, ID>> = { it },
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
     * Returns whether [subject] may authenticate.
     *
     * If this returns `false`, using an existing refresh token or creating a new session fails with 403 Forbidden.
     */
    context(server: ServerRuntime)
    public open suspend fun permitAuthentication(subject: SUBJECT): Boolean = true

    /**
     * Returns the latest instant at which a login for [subject] may expire, or `null` for no limit.
     *
     * This is reported as `maxExpiration` by [AuthEndpoints.proofsCheck] and caps the expiration of sessions created by [AuthEndpoints.login2]. It is not applied by [newSession], which uses the `expires` it is given. See [sessionStaleAfter] for inactivity-based expiration.
     */
    context(server: ServerRuntime)
    public abstract suspend fun sessionExpiration(subject: SUBJECT): Instant?

    /**
     * Returns how long a session for [subject] may go unused before it expires, or `null` to disable this.
     *
     * Unlike [sessionExpiration], this is a sliding window: each use resets the stale time to `now() + sessionStaleAfter()`.
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
            // Covers every way a session ends, including a superuser's REST update or delete.
            signals = { table ->
                sessionSignals(table)
                    .postChange { old, new -> if (old.terminated == null && new.terminated != null) terminateDerivedFrom(new._id) }
                    .postDelete { terminateDerivedFrom(it._id) }
            },
            log = { table ->
                table.postRawChanges { changes ->
                    for ((old, new) in changes) {
                        val wasLive = old != null && old.terminated == null
                        val isLive = new != null && new.terminated == null
                        if (!wasLive && isLive) {
                            serverRuntime.reportAuthEvent(AuthEvent.SessionCreated(new.subjectId.toString(), new._id, this?.request))
                        }
                        if (wasLive && !isLive) {
                            serverRuntime.reportAuthEvent(AuthEvent.SessionTerminated(old.subjectId.toString(), old._id, this?.request))
                        }
                    }
                }
            },
            permissions = {
                val auth = this.authOrNull
                val canUse: Condition<Session<SUBJECT, ID>> = when {
                    auth == null -> Condition.Never
                    else -> spath.subjectId eq auth.id
                }

                val isRoot = condition<Session<SUBJECT, ID>>(AuthRequirement.IsSuperUser.accepts(auth))

                ModelPermissions(
                    create = isRoot,
                    read = canUse or isRoot,
                    readMask = Mask(
                        listOf(
                            Condition.Never to modification(spath) { it.secretHash assign "" }
                        )
                    ),
                    update = isRoot,
                    // Sessions are terminated, never erased. The row is the only record that a
                    // session ever existed, and two comments in this file already promise it is
                    // "kept for audit trail" — but delete was exposed over REST, so a super-user
                    // could erase the evidence and nothing recorded that they had. Termination
                    // (Session.terminated) remains available and is what callers want; it goes
                    // through table(), which does not consult these permissions.
                    delete = Condition.Never,
                )
            }
        )

    /**
     * Returns the authentication carried by [request], or `null` if it carries no credentials.
     *
     * The token is read from the `Authorization` header, then the `Authorization` or `jwt` query parameter, then the `Authorization` cookie. It is parsed as an access token first, and as a refresh token if that fails. Prefer the header, since URLs are often logged.
     *
     * @throws UnauthorizedException if credentials are present but invalid.
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
     * Returns a new session for [subjectId] along with its refresh token.
     *
     * The refresh token holds the only plaintext copy of the session's secret, which cannot be recovered from the database.
     *
     * @param expires When the session expires, or `null` for never.
     * @param stale The initial stale time, which [sessionStaleAfter] then updates on each use.
     * @param scopes The scopes granted to the session, with full access by default.
     * @param oauthClient Currently unused.
     * @param derivedFrom The parent session if this is a sub-session.
     * @param authenticatedAt See [Session.authenticatedAt].
     * @throws ForbiddenException if the subject is not permitted to authenticate.
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
        authenticatedAt: Instant = now(),
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
            authenticatedAt = authenticatedAt,
            lastUsed = now(),
//            oauthClient = oauthClient,  TODO: OAuth
            derivedFrom = derivedFrom,
        ).also { sessionInfo.table().insertOne(it) }.let {
            it to RefreshToken(principal.name, it._id, secret)
        }
    }

    /** Returns the [Authentication] that represents this session's authorization state. */
    context(server: ServerRuntime)
    public fun Session<SUBJECT, ID>.toAuth(): Authentication<SUBJECT> = Authentication(
        principalType = principal,
        id = subjectId,
        sessionId = _id,
        issuedAt = authenticatedAt ?: createdAt,
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

    context(server: ServerRuntime)
    private suspend fun tokenRejected(reason: AuthEvent.TokenRejected.Reason, request: Request<*>) {
        if (generalSettings().debug) println("Auth failed: $reason")
        server.reportAuthEvent(AuthEvent.TokenRejected(reason, request))
    }

    context(server: ServerRuntime)
    private suspend fun Session<SUBJECT, ID>.rejected(reason: AuthEvent.SessionRejected.Reason, request: Request<*>) {
        if (generalSettings().debug) println("Auth failed: $reason ($_id)")
        server.reportAuthEvent(AuthEvent.SessionRejected(reason, subjectId.toString(), _id, request))
    }

    // Collected breadth-first, then terminated in one update. Recursing level by level through the postChange hook
    // overflows the stack on a long chain of sub-sessions when the database never suspends (RAM, JSON files).
    // `seen` stops a derivedFrom cycle, which a superuser could create through the REST endpoints.
    context(server: ServerRuntime)
    private suspend fun terminateDerivedFrom(root: Uuid) {
        val seen = hashSetOf(root)
        var parents = listOf(root)
        while (parents.isNotEmpty()) {
            parents = sessionInfo.table()
                .find((spath.derivedFrom inside parents) and (spath.terminated eq null))
                .toList()
                .map { it._id }
                .filter { it !in seen }
            seen += parents
        }
        sessionInfo.table().updateMany(
            (spath._id inside seen) and (spath.terminated eq null),
            modification(spath) { it.terminated assign now() },
        )
    }

    /**
     * Returns the session that the refresh token in [request] refers to, or `null` if the token is malformed.
     *
     * This checks the token's type, secret, expiration, staleness and termination, then records the user agent and IP and resets the stale time. That makes every use of a refresh token a database write.
     *
     * @throws UnauthorizedException if the token is well-formed but invalid.
     */
    context(server: ServerRuntime)
    private suspend fun RefreshToken.session(request: Request<*>): Session<SUBJECT, ID>? {
        if (!valid) {
            tokenRejected(AuthEvent.TokenRejected.Reason.Malformed, request)
            return null
        }
        if (type != principal.name) {
            tokenRejected(AuthEvent.TokenRejected.Reason.TypeMismatch, request)
            return null
        }

        val session = sessionInfo.table().get(_id) ?: run {
            tokenRejected(AuthEvent.TokenRejected.Reason.NoSuchSession, request)
            throw UnauthorizedException("No such session")
        }
        val subject = principal.fetch(session.subjectId)
        if(!permitAuthentication(subject)){
            session.rejected(AuthEvent.SessionRejected.Reason.AuthenticationNotPermitted, request)
            throw ForbiddenException()
        }
        // SECURITY: Constant-time hash comparison to prevent timing attacks
        if (!plainTextSecret.checkAgainstHash(session.secretHash)) {
            session.rejected(AuthEvent.SessionRejected.Reason.SecretMismatch, request)
            throw UnauthorizedException("Incorrect hash for session")
        }
        if ((session.expires ?: Instant.DISTANT_FUTURE) < now()) {
            session.rejected(AuthEvent.SessionRejected.Reason.Expired, request)
            throw UnauthorizedException("Session has expired (hard).")
        }
        if ((session.stale ?: Instant.DISTANT_FUTURE) < now()) {
            session.rejected(AuthEvent.SessionRejected.Reason.Stale, request)
            throw UnauthorizedException("Session has expired (stale).")
        }
        if (session.terminated != null) {
            session.rejected(AuthEvent.SessionRejected.Reason.Terminated, request)
            throw UnauthorizedException("Session has been terminated.")
        }

        // Lazy migration: if using old slow PBKDF2 hash, upgrade to fast SHA-256 hash
        val shouldMigrate = session.secretHash.isSlowHash()

        // Update session metadata on each use for audit trail and sliding expiration
        sessionInfo.table().updateOneById(_id, modification(spath) {
            it.lastUsed assign now()
            // Record only what was actually observed. These sets are the closest thing the system
            // has to an authentication audit trail, and a placeholder in them is indistinguishable
            // from a real observation: a literal "test" ip read as a genuine origin, and an empty
            // user agent as a genuine one. Absent is absent.
            it.userAgents addAll setOfNotNull(request.headers[HttpHeader.UserAgent]?.root)
            it.ips addAll setOf(request.sourceIp)

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
     * Exchanges a refresh token for an access token, requiring no other authentication.
     *
     * @throws BadRequestException if the refresh token format is invalid.
     * @throws UnauthorizedException if the refresh token is expired, revoked, or otherwise invalid.
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
     * Creates a sub-session of the current session, returning its refresh token.
     *
     * Its scopes cannot exceed the parent's, and requesting root (the default) grants the parent's scopes. Its expiration cannot exceed the parent's, it inherits the parent's stale time, and terminating the parent terminates it.
     *
     * @throws UnauthorizedException if the current session cannot be found, has no session ID, or is terminated.
     * @throws ForbiddenException if the requested scopes exceed the parent session's scopes.
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
                // An access token outlives its session's termination by a few minutes.
                if (session.terminated != null) throw UnauthorizedException("Session has been terminated.")
                val scopes = if (request.scopes == setOf(GrantedScope.root)) session.scopes else request.scopes
                if (!session.scopes.meetsRequirements(scopes.mapTo(HashSet()) { RequiredScope(it.asString) }))
                    throw ForbiddenException("Sub-session scopes must be within the parent session's scopes.")

                newSession(
                    label = request.label,
                    subjectId = auth.id,
                    derivedFrom = sessionUuid,
                    scopes = scopes,
                    // Sub-session expires can't exceed parent session expiration
                    expires = session.expires
                        ?.let { minOf(it, request.expires ?: Instant.DISTANT_FUTURE) }
                        ?: request.expires,
                    stale = session.stale,
                    oauthClient = request.oauthClient,
                    authenticatedAt = session.authenticatedAt ?: session.createdAt,
                ).second.string
            }
        )

    /** Returns the authenticated subject's data, requiring the `auth:self` scope. */
    public val self: ApiHttpHandler<PathSpec0, SUBJECT, Unit, SUBJECT> =
        path.path("self").get bind explicitApiHttpHandler(
            summary = "Get Self",
            auth = principal.require(scopes = setOf(Scopes.self)),
            inputType = Unit.serializer(),
            outputType = principal.subjectSerializer,
            implementation = { _ -> auth.fetch() }
        )

    /**
     * Terminates the current session, requiring the `auth:sessions:terminate` scope.
     *
     * Its refresh token stops working, but access tokens already issued stay valid until they expire. Its sub-sessions are terminated too.
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
     * Terminates the session with the given ID, requiring the `auth:sessions:terminate` scope.
     *
     * @throws ForbiddenException if the session belongs to a different user.
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
 *    - Useful for "logout from all devices"
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