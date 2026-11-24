package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.currentRequestTrace
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.toTypedData
import com.lightningkite.lightningserver.sessions.token.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.database.optics.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.database.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.serializer
import kotlin.io.encoding.Base64
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val authorizationMethod = TelemetryKey.OfString("authorization.method")

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
 * @param tokenInvalidationChecks Whether access tokens can be revoked before they expire.
 */
public abstract class SessionManager<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    public val tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
    private val cache: Runtime<Cache>? = null,
    public val tokenInvalidationChecks: Boolean = false,
    /**
     * Decorates the session table, so a deployment can observe session creation, update and termination.
     *
     * The decorator is applied ahead of permissions, so it sees privileged and unprivileged access alike, and it sees reads as well as writes. To observe writes only, use `sessionInfo.registerChangeListener`.
     */
    private val sessionSignals: context(ServerRuntime) (Table<Session<SUBJECT, ID>>) -> Table<Session<SUBJECT, ID>> = { it },
    /**
     * Whether the refresh token cookie is marked `Secure`. Turn it off only for local development over plain http,
     * where native clients and Safari won't send a `Secure` cookie.
     */
    private val refreshTokenCookieSecure: Runtime<Boolean> = Runtime { true },
    /**
     * Whether [read] accepts a refresh token as a bearer credential, for legacy clients that send one instead of an
     * access token. Off by default: the long-lived token then travels on every request, and each use writes to the
     * database. A rotating session's refresh token is never accepted this way: see [rotateRefreshToken].
     */
    private val acceptRefreshTokenAsBearer: Runtime<Boolean> = Runtime { false },
) : ServerBuilder(), Authentication.Reader<SUBJECT> {
    public object Scopes {
        public val self: RequiredScope = RequiredScope("auth:self")
        public val sessions: RequiredScope = RequiredScope("auth:sessions")
        public val terminate: RequiredScope = sessions.subscope(Subscope("terminate"))
    }

    init {
        if (tokenInvalidationChecks && cache == null)
            throw IllegalArgumentException("cache is required when tokenInvalidationChecks = true")
        register(principal)
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
     * Determines the most a subject is currently allowed to access.
     *
     * Applied whenever a session becomes an [Authentication] (in [toAuth]), not when the session is created: the
     * session keeps the scopes it was created with, and the effective scopes are the intersection of those and
     * this result. A change here takes effect on the subject's next access token without re-authenticating,
     * e.g. limiting a subject to MFA setup until it's established, then returning root.
     *
     * A root scope allows access to everything.
     *
     * @param subject The subject whose scopes are being limited
     * @return The scopes that subject can access.
     */
    context(server: ServerRuntime)
    public open suspend fun permitScopes(subject: SUBJECT): Set<GrantedScope> = setOf(GrantedScope.root)

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

    private val spath = IdentityLens(Session.serializer(principal.subjectSerializer, principal.idSerializer))

    public val sessionInfo: ModelInfo<SUBJECT, Session<SUBJECT, ID>, Uuid> =
        database.explicitModelInfo(
            auth = principal.require(scopes = setOf(Scopes.sessions)),
            serializer = Session.serializer(principal.subjectSerializer, principal.idSerializer),
            idSerializer = Uuid.serializer(),
            tableName = principal.name + "Session",
            // Covers every way a session ends, including a superuser's REST update or delete.
            signals = { table ->
                sessionSignals(table)
                    .postRawChanges { changes ->
                    val ended = changes.mapNotNull { change ->
                        val old = change.old ?: return@mapNotNull null
                        val new = change.new
                        if (old.terminated == null && (new == null || new.terminated != null)) old else null
                    }
                    if (ended.isEmpty()) return@postRawChanges
                    terminateDerivedFrom(ended.map { it._id })
                    try {
                        if (tokenInvalidationChecks) invalidateSessionsWithLiveTokens(ended)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to post session invalidations to the cache" }
                    }
                }
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

                val isRoot = Condition(AuthRequirement.IsSuperUser.accepts(auth))

                ModelPermissions(
                    create = isRoot,
                    read = canUse or isRoot,
                    readMask = Mask(
                        listOf(
                            Condition.Never to Modification(spath) {
                                it.secretHash assign ""
                                it.previousSecretHash assign null
                            }
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

    private fun sessionInvalidationCacheKey(sessionId: Uuid) = "invalid-session:$sessionId"
    private fun subjectInvalidationCacheKey(subjectType: String, subjectId: ID) =
        "invalid-subject:$subjectType:$subjectId"

    context(server: ServerRuntime)
    private suspend fun isTokenInvalidated(key: String, auth: Authentication<SUBJECT>): Boolean {
        val time = cache!!().get<Instant>(key) ?: return false
        return time > (auth.tokenIssuedAt ?: return true)
    }


    /**
     * Returns the authentication carried by [request], or `null` if it carries no credentials.
     *
     * The access token is read from the `Authorization` header (`Bearer` optional). A WebSocket connect request may
     * instead carry it in an `Authorization` or `jwt` query parameter, since browsers can't set headers on a WebSocket.
     * A refresh token is rejected unless [acceptRefreshTokenAsBearer] is on: otherwise only the token endpoints
     * ([tokenSimple], [tokenFromCookie]) accept them.
     *
     * @throws BadRequestException if a request other than a WebSocket connect carries a token in its URL, where it
     *   would end up in logs.
     * @throws UnauthorizedException if the token is a refresh token, or an invalid access token.
     */
    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<SUBJECT>? {
        val headerToken = request.headers[HttpHeader.Authorization]?.root?.removePrefix("bearer ")?.removePrefix("Bearer ")
        val queryToken = request.queryParameters.find {
            it.first.equals(HttpHeader.Authorization, ignoreCase = true) || it.first == "jwt"
        }?.second?.replace(' ', '+')
        if (queryToken != null && request !is WebSocketConnectRequest<*>)
            throw BadRequestException("Send the access token in the Authorization header, not the URL.")
        val token = headerToken ?: queryToken ?: return null

        val refreshToken = RefreshToken(token)
        if (refreshToken.valid) {
            if (!acceptRefreshTokenAsBearer())
                throw UnauthorizedException("A refresh token is not an access token; exchange it at a token endpoint.")
            return refreshToken.session(request)
                ?.let { (session, subject) -> session.toAuth(subject) }
                ?.also { auth ->
                    currentRequestTrace()?.enrich(TelemetryAttributes {
                        putIfNotNull(TelemetryKeys.Session.id, auth.sessionId)
                        put(TelemetryKeys.User.id, auth.id.toString())
                        put(authorizationMethod, "refresh token")
                    })
                }
        }

        try {
            return tokenFormat().read(principal, token)
                ?.also { auth ->

                    // A way to invalidate access tokens but not expire a session
                    if (tokenInvalidationChecks &&
                        coroutineScope {
                            listOfNotNull(
                                auth.sessionId
                                    ?.let(Uuid::parseOrNull)
                                    ?.let { sessionId ->
                                        async { isTokenInvalidated(sessionInvalidationCacheKey(sessionId), auth) }
                                    },
                                async { isTokenInvalidated(subjectInvalidationCacheKey(principal.name, auth.id), auth) }
                            )
                        }
                            .awaitAll()
                            .any { it }
                    ) {
                        throw UnauthorizedException("Invalid access token")
                    }

                    currentRequestTrace()?.enrich(TelemetryAttributes {
                        putIfNotNull(TelemetryKeys.Session.id, auth.sessionId)
                        put(TelemetryKeys.User.id, auth.id.toString())
                        put(authorizationMethod, "access token")
                    })
                }
        } catch (e: TokenException) {
            throw UnauthorizedException(e.message ?: "JWT issue")
        }
    }

    /**
     * Rejects every access token minted from the session [sessionId] before now, without ending the session. Its
     * refresh token keeps working, so the client can get a new access token.
     *
     * Requires [tokenInvalidationChecks]; otherwise this only logs a warning.
     */
    context(server: ServerRuntime)
    public suspend fun invalidateSession(sessionId: Uuid) {
        if (tokenInvalidationChecks) {
            cache!!().set(sessionInvalidationCacheKey(sessionId), now(), tokenFormat().expiration)
        } else {
            logger.warn { "SessionManager invalidateSession was called but tokenInvalidationChecks is false" }
        }
    }

    // An access token is only minted by [tokenSimple], which goes through [session] and sets lastUsed first. A session
    // last used more than one token lifetime ago therefore has no live access token and needs no cache entry.
    context(server: ServerRuntime)
    private suspend fun invalidateSessionsWithLiveTokens(sessions: List<Session<SUBJECT, ID>>) {
        val cutoff = now() - tokenFormat().expiration
        sessions
            .filter { it.lastUsed >= cutoff }
            .chunked(INVALIDATION_CONCURRENCY)
            .forEach { chunk ->
                coroutineScope { chunk.map { async { invalidateSession(it._id) } }.awaitAll() }
            }
    }

    /**
     * Rejects every access token minted for the subject [subjectID] before now, across all of their sessions, without
     * ending any session. Their refresh tokens keep working, so clients can get new access tokens.
     *
     * Requires [tokenInvalidationChecks]; otherwise this only logs a warning.
     */
    context(server: ServerRuntime)
    public suspend fun invalidateSubject(subjectID: ID) {
        if (tokenInvalidationChecks) {
            cache!!().set(subjectInvalidationCacheKey(principal.name, subjectID), now(), tokenFormat().expiration)
        } else {
            logger.warn { "SessionManager invalidateSubject was called but tokenInvalidationChecks is false" }
        }
    }

    /**
     * Returns a new session for [subjectId] along with its refresh token.
     *
     * The refresh token holds the only plaintext copy of the session's secret, which cannot be recovered from the database.
     *
     * @param subjectId The ID of the subject (user/entity) this session belongs to
     * @param label Optional human-readable label for the session (e.g., "Mobile App", "Web Browser")
     * @param expires Hard expiration time (overrides [sessionExpiration] if provided)
     * @param expires When the session expires, or `null` for never.
     * @param stale The initial stale time, which [sessionStaleAfter] then updates on each use.
     * @param scopes The scopes granted to the session, with full access by default.
     * @param oauthClient The OAuth client the session is issued to; see [Session.oauthClient].
     * @param audience The resource the session's access tokens are for; see [Session.audience].
     * @param derivedFrom The parent session if this is a sub-session.
     * @param authenticatedAt See [Session.authenticatedAt].
     * @throws ForbiddenException if the subject is not permitted to authenticate.
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
        audience: String? = null,
        derivedFrom: Uuid? = null,
        authenticatedAt: Instant = now(),
    ): Pair<Session<SUBJECT, ID>, RefreshToken> = newSession(
        subject = principal.fetch(subjectId),
        label = label,
        expires = expires,
        stale = stale,
        scopes = scopes,
        oauthClient = oauthClient,
        audience = audience,
        derivedFrom = derivedFrom,
        authenticatedAt = authenticatedAt,
    )

    /**
     * Creates a new session for an already-loaded subject, saving the load [newSession] by ID would do.
     *
     * Otherwise identical to the [newSession] overload that takes a subject ID; see it for details.
     *
     * @param subject The subject (user/entity) this session belongs to
     * @return Pair of the created Session and its RefreshToken (containing plaintext secret)
     */
    context(_: ServerRuntime)
    public suspend fun newSession(
        subject: SUBJECT,
        label: String? = null,
        expires: Instant? = null,
        stale: Instant? = null,
        scopes: Set<GrantedScope> = setOf(GrantedScope.root),
        oauthClient: String? = null,
        audience: String? = null,
        derivedFrom: Uuid? = null,
        authenticatedAt: Instant = now(),
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        // Generate a cryptographically secure 24-byte random secret
        val secret = Base64.encode(CryptographyRandom.nextBytes(24))

        if (!permitAuthentication(subject)) {
            throw ForbiddenException()
        }

        return Session<SUBJECT, ID>(
            secretHash = secret.fastHash(),  // SECURITY: Only the hash is stored, never the plaintext. Using fast hash since session tokens are high-entropy.
            subjectId = subject._id,
            label = label,
            expires = expires,
            stale = stale,
            scopes = scopes,
            createdAt = now(),
            authenticatedAt = authenticatedAt,
            lastUsed = now(),
            oauthClient = oauthClient,
            audience = audience,
            // Rotating from the start, an OAuth client's refresh token is refused by [tokenSimple] and [read], which
            // would skip the OAuth token endpoint's client checks and the reuse detection.
            rotatedAt = if (oauthClient != null) now() else null,
            derivedFrom = derivedFrom,
        ).also { sessionInfo.table().insertOne(it) }.let {
            it to RefreshToken(principal.name, it._id, secret)
        }
    }

    /** Returns the [Authentication] that represents this session's authorization state. */
    context(server: ServerRuntime)
    public suspend fun Session<SUBJECT, ID>.toAuth(): Authentication<SUBJECT> = toAuth(principal.fetch(subjectId))

    /** Returns the [Authentication] that represents this session's authorization state. */
    context(server: ServerRuntime)
    public suspend fun Session<SUBJECT, ID>.toAuth(subject: SUBJECT): Authentication<SUBJECT> {
        require(subject._id == subjectId) { "Subject ${subject._id} does not own session $_id" }
        val allowedScopes = permitScopes(subject)
        val effectiveScopes = when {
            GrantedScope.root in scopes -> allowedScopes
            GrantedScope.root in allowedScopes -> scopes
            else -> scopes.filterTo(HashSet()) { granted ->
                allowedScopes.any { it.meetsRequirements(RequiredScope(granted.asString)) }
            } + allowedScopes.filterTo(HashSet()) { allowed ->
                scopes.any { it.meetsRequirements(RequiredScope(allowed.asString)) }
            }
        }
        return Authentication(
            principalType = principal,
            id = subjectId,
            sessionId = _id.toString(),
            sessionIssuedAt = authenticatedAt ?: createdAt,
            scopes = effectiveScopes,
            cache = SerializableCache<Authentication<SUBJECT>>().apply { set(principal.subjectCacheKey, subject) },
            audience = audience,
        )
    }

    // Ownership is part of the update's condition, so checking it costs no separate read.
    context(server: ServerRuntime)
    private suspend fun terminateSessionById(id: Uuid, requireOwnershipOf: ID?) {
        val condition = if (requireOwnershipOf == null) spath._id eq id
        else spath._id.eq(id) and spath.subjectId.eq(requireOwnershipOf)
        val updated = sessionInfo.table().updateOne(condition, modification(spath) { it.terminated assign now() })
        if (requireOwnershipOf != null && updated.new == null) throw ForbiddenException()
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

    // Collected breadth-first from every root at once, then terminated in one update. Recursing level by level
    // through the change hook overflows the stack on a long chain of sub-sessions when the database never suspends
    // (RAM, JSON files). `seen` stops a derivedFrom cycle, which a superuser could create through the REST endpoints.
    // The roots are already terminated or deleted, so with no descendants there's nothing to update. That's also the
    // case when this update's change hook runs again for the descendants, which is where their tokens are invalidated.
    context(server: ServerRuntime)
    private suspend fun terminateDerivedFrom(roots: Collection<Uuid>) {
        val seen = roots.toHashSet()
        var parents = roots.toList()
        while (parents.isNotEmpty()) {
            parents = sessionInfo.table()
                .find((spath.derivedFrom inside parents) and (spath.terminated eq null))
                .toList()
                .map { it._id }
                .filter { it !in seen }
            seen += parents
        }
        seen -= roots.toSet()
        if (seen.isEmpty()) return
        sessionInfo.table().updateMany(
            (spath._id inside seen) and (spath.terminated eq null),
            Modification(spath) { it.terminated assign now() },
        )
    }

    /**
     * Returns the live session that this refresh token refers to, and whether the token is the session's previous one
     * rather than its current one, or `null` if the token is malformed.
     *
     * A previous token is only returned within [ROTATION_GRACE] of the rotation that retired it. After that, presenting
     * it means someone else may hold a copy, so the session is terminated.
     *
     * @throws UnauthorizedException if the token is well-formed but its session is missing, expired, stale or
     *   terminated, its secret matches neither the current nor the previous one, or it is a reused previous one.
     */
    context(server: ServerRuntime)
    private suspend fun RefreshToken.liveSession(request: Request<*>): Pair<Session<SUBJECT, ID>, Boolean>? {
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
        // SECURITY: Constant-time hash comparison to prevent timing attacks
        val isPrevious = when {
            plainTextSecret.checkAgainstHash(session.secretHash) -> false
            session.previousSecretHash?.let { plainTextSecret.checkAgainstHash(it) } == true -> true
            else -> {
                session.rejected(AuthEvent.SessionRejected.Reason.SecretMismatch, request)
                throw UnauthorizedException("Incorrect hash for session")
            }
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
        if (isPrevious && now() >= session.rotatedAt!! + ROTATION_GRACE) {
            session.rejected(AuthEvent.SessionRejected.Reason.Reused, request)
            terminateSessionById(session._id, null)
            throw UnauthorizedException("This refresh token was already used, so its session has been terminated.")
        }
        return session to isPrevious
    }

    /**
     * Returns the session [token] belongs to, whether live or not, if it is the session's current refresh token or its
     * previous one within [ROTATION_GRACE] of the rotation, which [rotateRefreshToken] would still accept. Otherwise
     * `null`. Unlike using the token, this records nothing and ends no session, for endpoints that only identify a
     * token, such as OAuth revocation and introspection.
     */
    context(server: ServerRuntime)
    public suspend fun refreshTokenSession(token: RefreshToken): Session<SUBJECT, ID>? {
        if (!token.valid || token.type != principal.name) return null
        val id = try {
            token._id
        } catch (e: IllegalArgumentException) {
            return null
        }
        val session = sessionInfo.table().get(id) ?: return null
        val isCurrent = token.plainTextSecret.checkAgainstHash(session.secretHash)
        val isPreviousInGrace = session.previousSecretHash?.let { token.plainTextSecret.checkAgainstHash(it) } == true &&
            now() < session.rotatedAt!! + ROTATION_GRACE
        return session.takeIf { isCurrent || isPreviousInGrace }
    }

    // Deterministic, so the server can re-derive the current secret from the previous one, and two requests rotating
    // the same secret at once arrive at the same next one.
    private val rotationSigner = secretBasis.signer("refreshTokenRotation")

    context(server: ServerRuntime)
    private suspend fun nextSecret(secret: String): String =
        Base64.encode(rotationSigner.await().sign(secret.encodeToByteArray()))

    /**
     * Checks that the subject of [session] may still authenticate, then records this use: the user agent and IP, a
     * reset stale time, and the last-used time. That makes every use of a refresh token a database write.
     *
     * @throws ForbiddenException if [permitAuthentication] turns the subject away.
     */
    context(server: ServerRuntime)
    private suspend fun Session<SUBJECT, ID>.recordUse(request: Request<*>): SUBJECT {
        // Loaded only once the token itself checks out, so a bad token never costs a subject load
        val subject = principal.fetch(subjectId)
        if (!permitAuthentication(subject)) {
            rejected(AuthEvent.SessionRejected.Reason.AuthenticationNotPermitted, request)
            throw ForbiddenException()
        }

        // Update session metadata on each use for audit trail and sliding expiration
        sessionInfo.table().updateOneById(_id, Modification(spath) {
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
        })
        return subject
    }

    /**
     * Returns the session that this refresh token refers to, or `null` if the token is malformed. A rotating session's
     * tokens are refused: see [rotateRefreshToken].
     *
     * @throws UnauthorizedException if the token is well-formed but invalid, or belongs to a rotating session.
     */
    context(server: ServerRuntime)
    private suspend fun RefreshToken.session(request: Request<*>): Pair<Session<SUBJECT, ID>, SUBJECT>? {
        val (session, _) = liveSession(request) ?: return null
        // Accepting it here would let it skip rotation and its reuse detection.
        if (session.rotatedAt != null) {
            session.rejected(AuthEvent.SessionRejected.Reason.RotatingSession, request)
            throw UnauthorizedException("This session's refresh token rotates, so it only works at the endpoint that rotates it.")
        }
        // Lazy migration from the old slow PBKDF2 hash to the fast SHA-256 one
        if (session.secretHash.isSlowHash()) {
            sessionInfo.table().updateOneById(session._id, Modification(spath) { it.secretHash assign plainTextSecret.fastHash() })
        }
        return session to session.recordUse(request)
    }

    /**
     * Exchanges [presented] for its session's next refresh token, returning that with the session and subject, or
     * `null` if [presented] is malformed. The session rotates from then on, and [tokenSimple] and [read] refuse its
     * tokens.
     *
     * - The current token is replaced by the next one, an HMAC of it with a key derived from `secretBasis`, and becomes
     *   the previous one. Two requests rotating the same token at once both get the same next one.
     * - The previous token, within [ROTATION_GRACE] of the rotation, gets the current one back without rotating: its
     *   response may have been lost, or crossed another tab's request.
     * - The previous token after that means someone else may hold a copy: the session is terminated, with
     *   [AuthEvent.SessionRejected.Reason.Reused].
     * - Any older token can't be told from a wrong one, and is refused without ending the session.
     *
     * Only hashes are stored, as for any refresh token. Changing `secretBasis` makes the current token impossible to
     * re-derive, so a previous one is then refused, also without ending the session; the current one still rotates.
     * The rotation key is as strong as `secretBasis`, which is large and random.
     *
     * The session returned is as it was read, before this rotation.
     *
     * @throws UnauthorizedException if the token is well-formed but invalid or retired.
     * @throws ForbiddenException if [permitAuthentication] turns the subject away.
     */
    context(server: ServerRuntime)
    public suspend fun rotateRefreshToken(presented: RefreshToken, request: Request<*>): RotatedRefreshToken<SUBJECT, ID>? {
        val (session, isPrevious) = presented.liveSession(request) ?: return null
        if (isPrevious) {
            val current = nextSecret(presented.plainTextSecret)
            // Check the derived one to make sure it's right; if not, we'll just have to error
            if (!current.checkAgainstHash(session.secretHash)) {
                session.rejected(AuthEvent.SessionRejected.Reason.SecretMismatch, request)
                throw UnauthorizedException("This refresh token can no longer be renewed.")
            }
            return RotatedRefreshToken(session, session.recordUse(request), RefreshToken(principal.name, session._id, current))
        }

        val subject = session.recordUse(request)
        val next = nextSecret(presented.plainTextSecret)
        // Conditional on the current hash, so of two requests rotating the same token only one writes. The other's
        // next secret is the same.
        sessionInfo.table().updateOne(
            (spath._id eq session._id) and (spath.secretHash eq session.secretHash),
            Modification(spath) {
                it.secretHash assign next.fastHash()
                it.previousSecretHash assign session.secretHash
                it.rotatedAt assign now()
            },
        )
        return RotatedRefreshToken(session, subject, RefreshToken(principal.name, session._id, next))
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
                val (session, subject) = RefreshToken(refresh).session(request)
                    ?: throw BadRequestException("Refresh token not recognized")

                // Access tokens are minted only right after a refresh token use has set lastUsed, as [session] just did.
                // invalidateSessionsWithLiveTokens relies on that; any new mint path must update lastUsed too.
                tokenFormat().create(principal, session.toAuth(subject).apply { precache(principal.precache) })
            }
        )

    /**
     * Creates a sub-session of the session behind [parent], returning it and its refresh token.
     *
     * Its scopes cannot exceed the parent's, and requesting root (the default) grants the parent's scopes. Its expiration cannot exceed the parent's, it inherits the parent's stale time, authentication time and audience, and terminating the parent terminates it.
     *
     * There is no endpoint for this: anyone holding an access token could have used one to mint a long-lived refresh token.
     *
     * @throws UnauthorizedException if [parent] has no session ID, or its session cannot be found or is terminated.
     * @throws ForbiddenException if [scopes] exceed the parent session's scopes.
     */
    context(_: ServerRuntime)
    public suspend fun newSubSession(
        parent: Authentication<SUBJECT>,
        label: String? = null,
        scopes: Set<GrantedScope> = setOf(GrantedScope.root),
        expires: Instant? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        // TODO: We'll probably change this in the future to require 'sudo mode' / recent auth
        val sessionUuid = parent.sessionId?.let(Uuid::parse) ?: throw UnauthorizedException()
        val session = sessionInfo.table().get(sessionUuid)
            ?: throw UnauthorizedException()
        // An access token outlives its session's termination by a few minutes.
        if (session.terminated != null) throw UnauthorizedException("Session has been terminated.")
        val grantedScopes = if (scopes == setOf(GrantedScope.root)) session.scopes else scopes
        if (!session.scopes.meetsRequirements(grantedScopes.mapTo(HashSet()) { RequiredScope(it.asString) }))
            throw ForbiddenException("Sub-session scopes must be within the parent session's scopes.")

        return newSession(
            label = label,
            subject = parent.fetch(),
            derivedFrom = sessionUuid,
            scopes = grantedScopes,
            // Sub-session expires can't exceed parent session expiration
            expires = session.expires
                ?.let { minOf(it, expires ?: Instant.DISTANT_FUTURE) }
                ?: expires,
            stale = session.stale,
            authenticatedAt = session.authenticatedAt ?: session.createdAt,
            audience = session.audience,
        )
    }

    /**
     * Exchanges the refresh token in the [refreshTokenCookieName] cookie for an access token, like [tokenSimple].
     *
     * The cookie is set by [setRefreshTokenCookie] and is sent only to this endpoint. Each call replaces it with the
     * session's next refresh token; see [rotateRefreshToken].
     *
     * @throws UnauthorizedException if the cookie is missing, or its refresh token is expired, revoked, retired, or
     *   otherwise invalid.
     */
    public val tokenFromCookie: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, String> =
        path.path("token").path("cookie").post bind object : ApiHttpHandler<PathSpec0, HasId<*>?, Unit, String> by explicitApiHttpHandler(
            auth = noAuth,
            inputType = Unit.serializer(),
            outputType = String.serializer(),
            summary = "Get Token From Cookie",
            description = "Returns an access token for the refresh token held in the HttpOnly cookie set by setRefreshTokenCookie.",
            implementation = { _ -> throw UnsupportedOperationException("getTokenFromCookie sets a cookie, so it only works over HTTP.") },
        ) {
            @OverrideOnly
            context(server: ServerRuntime)
            override suspend fun handle(request: HttpRequest<PathSpec0>): HttpResponse {
                val refresh = request.headers.cookies[refreshTokenCookieName]
                    ?: throw UnauthorizedException("No refresh token cookie")
                val rotated = rotateRefreshToken(RefreshToken(refresh), request)
                    ?: throw UnauthorizedException("Refresh token not recognized")
                // Like tokenSimple, the refresh token use has just set lastUsed; see the note there.
                val accessToken = tokenFormat().create(principal, rotated.session.toAuth(rotated.subject).apply { precache(principal.precache) })
                return HttpResponse(
                    body = accessToken.toTypedData(request.headers.accept, outputType),
                    headers = HttpHeaders {
                        add(HttpHeader.Vary, HttpHeader.Accept)
                        setRefreshCookie(rotated.refreshToken, rotated.session)
                    },
                )
            }
        }

    /**
     * Validates a refresh token and moves its session into an HttpOnly cookie that only [tokenFromCookie] receives.
     *
     * This is how a web client keeps its session out of reach of page scripts: log in with [AuthEndpoints.login2], send
     * the refresh token here, then forget it and get access tokens from [tokenFromCookie]. The cookie holds the
     * session's next refresh token rather than the one sent, which passed through page scripts and is now retired, as
     * [rotateRefreshToken] describes.
     *
     * @throws BadRequestException if the refresh token format is invalid.
     * @throws UnauthorizedException if the refresh token is expired, revoked, or otherwise invalid.
     */
    public val setRefreshTokenCookie: ApiHttpHandler<PathSpec0, HasId<*>?, String, Unit> =
        path.path("token").path("set-cookie").post bind object : ApiHttpHandler<PathSpec0, HasId<*>?, String, Unit> by explicitApiHttpHandler(
            auth = noAuth,
            inputType = String.serializer(),
            outputType = Unit.serializer(),
            summary = "Set Refresh Token Cookie",
            description = "Moves a web client's refresh token into an HttpOnly cookie after login, so page scripts never need to keep it. Get access tokens from it with getTokenFromCookie.",
            implementation = { _ -> throw UnsupportedOperationException("setRefreshTokenCookie sets a cookie, so it only works over HTTP.") },
        ) {
            @OverrideOnly
            context(server: ServerRuntime)
            override suspend fun handle(request: HttpRequest<PathSpec0>): HttpResponse {
                // A cross-site HTML form can send form or text bodies but not JSON, so this keeps another site from
                // planting its own session in the browser (login CSRF).
                val body = request.body?.takeIf { it.mediaType.copy(parameters = mapOf()) == MediaType.Application.Json }
                    ?: throw BadRequestException("The refresh token must be sent as application/json.")
                val rotated = rotateRefreshToken(RefreshToken(body.parse(inputType)), request)
                    ?: throw BadRequestException("Refresh token not recognized")
                return HttpResponse(
                    status = successCode,
                    headers = HttpHeaders { setRefreshCookie(rotated.refreshToken, rotated.session) },
                )
            }
        }

    context(server: ServerRuntime)
    private fun HttpHeaders.Builder.setRefreshCookie(token: RefreshToken, session: Session<SUBJECT, ID>) = setCookie(
        name = refreshTokenCookieName,
        value = token.string,
        // Browsers cap a cookie's lifetime at 400 days whatever it asks for.
        expiresAt = session.expires ?: (now() + 400.days),
        path = refreshTokenCookiePath(),
        secure = refreshTokenCookieSecure(),
        httpOnly = true,
        sameSite = HttpHeaders.SameSite.Strict,
    )

    /** The name of the cookie [setRefreshTokenCookie] sets and [tokenFromCookie] reads. */
    public val refreshTokenCookieName: String = "RefreshToken"

    // Scoped to the one endpoint that reads it, so no other request carries the long-lived token.
    context(server: ServerRuntime)
    private fun refreshTokenCookiePath(): String =
        URI(generalSettings().publicUrl).rawPath.trimEnd('/') + tokenFromCookie.location.path.toString()

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
     * Its refresh token stops working, but access tokens already issued stay valid until they expire. Its sub-sessions are
     * terminated too. The response also deletes the [refreshTokenCookieName] cookie.
     */
    public val sessionTerminate: ApiHttpHandler<PathSpec0, SUBJECT, Unit, Unit> =
        path.path("terminate").post bind object : ApiHttpHandler<PathSpec0, SUBJECT, Unit, Unit> by explicitApiHttpHandler(
            summary = "Terminate Current Session",
            functionName = "terminateSession",
            auth = principal.require(scopes = setOf(Scopes.terminate)),
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            errorCases = listOf(),
            implementation = { _ -> terminateSessionById(Uuid.parse(auth.sessionId!!), null) },
        ) {
            @OverrideOnly
            context(server: ServerRuntime)
            override suspend fun handle(request: HttpRequest<PathSpec0>): HttpResponse {
                @OptIn(OverrideOnly::class)
                val response = super.handle(request)
                return response.copy(headers = response.headers.copy {
                    setCookie(
                        name = refreshTokenCookieName,
                        value = "deleted",
                        maxAge = 0,
                        path = refreshTokenCookiePath(),
                        secure = refreshTokenCookieSecure(),
                        httpOnly = true,
                        sameSite = HttpHeaders.SameSite.Strict,
                    )
                })
            }
        }

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

private const val INVALIDATION_CONCURRENCY: Int = 100

// Long enough for another tab's request that crossed a rotation to arrive, short enough that a stolen copy rarely fits.
private val ROTATION_GRACE: Duration = 60.seconds

/** The result of [SessionManager.rotateRefreshToken]: the session, its subject, and the refresh token to hand out. */
public data class RotatedRefreshToken<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    val session: Session<SUBJECT, ID>,
    val subject: SUBJECT,
    val refreshToken: RefreshToken,
)

/*
 * TODO: SessionManager API Improvements and Recommendations
 *
 * 1. BULK SESSION TERMINATION
 *    - Add endpoint to terminate all sessions for a user (except current)
 *    - Useful for "logout from all devices"
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
