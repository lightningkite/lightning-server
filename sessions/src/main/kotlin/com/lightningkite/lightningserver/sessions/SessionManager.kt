package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.GrantedScopes
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.lightningserver.auth.auth
import com.lightningkite.lightningserver.auth.authReaders
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenException
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Mask
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.updateOneById
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.builtins.serializer
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

public abstract class SessionManager<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    public val tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : ServerBuilder(), Authentication.Reader<SUBJECT> {
    public companion object {
        public val sessionsScope: RequiredScope = RequiredScope("auth:sessions")
        public val selfScope: RequiredScope = RequiredScope("auth:self")
    }
    init {
        register(principal)
    }

    init {
        authReaders.register(this)
    }

    context(server: ServerRuntime)
    public abstract suspend fun sessionExpiration(subject: SUBJECT): Instant?

    context(server: ServerRuntime)
    public abstract suspend fun sessionStaleAfter(subject: SUBJECT): Duration?

    private val spath = Session.path(principal.subjectSerializer, principal.idSerializer)

    public val belongsToInterface: Documentable.OldInterfaceInfo = Documentable.OldInterfaceInfo( "UserAuthClientEndpoints", listOf(principal.idSerializer))
    public val loggedInBelongsToInterface: Documentable.OldInterfaceInfo = Documentable.OldInterfaceInfo(
        "AuthenticatedUserAuthClientEndpoints",
        listOf(principal.subjectSerializer, principal.idSerializer)
    )

    public val sessionInfo: ModelInfo<SUBJECT, Session<SUBJECT, ID>, Uuid> =
        database.modelInfo(
            auth = principal.auth(scopes = setOf(sessionsScope)),
            serializer = Session.serializer(principal.subjectSerializer, principal.idSerializer),
            idSerializer = Uuid.serializer(),
            collectionName = principal.name + "Session",
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

    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<SUBJECT>? {
        val token =
            request.headers[HttpHeader.Authorization]?.root?.removePrefix("bearer ")?.removePrefix("Bearer ")
                ?: request.queryParameters.find {
                    it.first.equals(HttpHeader.Authorization, ignoreCase = true)
                }?.second?.replace(' ', '+')
                ?: request.queryParameters.find { it.first == "jwt" }?.second?.replace(' ', '+')
                ?: request.headers.cookies[HttpHeader.Authorization]
                ?: return null

        try {
            return tokenFormat().read(principal, token) ?: RefreshToken(token).session(request)?.toAuth()
        } catch (e: TokenException) {
            throw UnauthorizedException(e.message ?: "JWT issue")
        }
    }

    context(_: ServerRuntime)
    protected suspend fun newSession(
        subjectId: ID,
        label: String? = null,
        expires: Instant? = null,
        stale: Instant? = null,
        scopes: Set<GrantedScope> = GrantedScopes.root,
        oauthClient: String? = null,
        derivedFrom: Uuid? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        val secret = Base64.encode(CryptographyRandom.nextBytes(24))

        return Session<SUBJECT, ID>(
            secretHash = secret.secureHash(),
            subjectId = subjectId,
            label = label,
            expires = expires,
            stale = stale,
            scopes = scopes,
            createdAt = now(),
            lastUsed = now(),
//            oauthClient = oauthClient,  TODO: OAuth
            derivedFrom = derivedFrom,
        ).also { sessionInfo.collection().insertOne(it) }.let {
            it to RefreshToken(principal.name, it._id, secret)
        }
    }

    context(server: ServerRuntime)
    public fun Session<SUBJECT, ID>.toAuth(): Authentication<SUBJECT> = Authentication(
        principalType = principal,
        id = subjectId,
        sessionId = _id,
        issuedAt = createdAt,
        scopes = scopes,
    )

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
        val session = sessionInfo.collection().get(_id) ?: run {
            if (generalSettings().debug) println("No such session")
            throw UnauthorizedException("No such session")
        }
        if (!plainTextSecret.checkAgainstHash(session.secretHash)) {
            if (generalSettings().debug) println("Auth failed because hash verification failed ($plainTextSecret vs ${session.secretHash})")
            throw UnauthorizedException("Incorrect hash for session")
        }
        if ((session.expires ?: Instant.DISTANT_FUTURE) < now()) {
            if (generalSettings().debug) println("Auth failed because (session.expires ?: Instant.DISTANT_FUTURE) < now()")
            throw UnauthorizedException("Session has expired.")
        }
        if ((session.stale ?: Instant.DISTANT_FUTURE) < now()) {
            if (generalSettings().debug) println("Auth failed because (session.stale ?: Instant.DISTANT_FUTURE) < now()")
            throw UnauthorizedException("Session has expired.")
        }
        if (session.terminated != null) {
            if (generalSettings().debug) println("Auth failed because session.terminated != null")
            throw UnauthorizedException("Session has been terminated.")
        }
        sessionInfo.collection().updateOneById(_id, modification(spath) {
            it.lastUsed assign now()
            it.userAgents addAll setOf(request?.headers?.get(HttpHeader.UserAgent)?.root ?: "")
            it.ips addAll setOf(request?.sourceIp ?: "test")

            sessionStaleAfter(principal.fetch(session.subjectId))?.let { length ->
                it.stale assign now() + length
            }
        })
        return session
    }

    public val tokenSimple: ApiHttpHandler<PathSpec0, HasId<AnyId>?, String, String> =
        path.path("token").path("simple").post bind ApiHttpHandler(
            auth = noAuth,
            belongsToInterface = belongsToInterface,
            summary = "Get Token Simple",
            implementation = { refresh: String ->
                val session = RefreshToken(refresh).session(request)
                    ?: throw BadRequestException("Refresh token not recognized")

                tokenFormat().create(principal, session.toAuth().apply { precache(principal.precache) })
            }
        )

    public val createSubSession: ApiHttpHandler<PathSpec0, SUBJECT, SubSessionRequest, String> =
        path.path("sub-session").post bind ApiHttpHandler(
            auth = sessionInfo.auth.subscope(ModelInfo.createSubscope),
            belongsToInterface = loggedInBelongsToInterface,
            inputType = SubSessionRequest.serializer(),
            outputType = String.serializer(),
            summary = "Create Sub Session",
            description = "Creates a session with more limited authorization",
            implementation = { request: SubSessionRequest ->
                val session = sessionInfo.collection().get(this.auth.sessionId ?: throw UnauthorizedException())
                    ?: throw UnauthorizedException()

                newSession(
                    label = request.label,
                    subjectId = auth.id,
                    derivedFrom = auth.sessionId,
                    scopes = request.scopes,
                    expires = session.expires
                        ?.let { minOf(it, request.expires ?: Instant.DISTANT_FUTURE) }
                        ?: request.expires,
                    stale = session.stale,
                    oauthClient = request.oauthClient,
                ).second.string
            }
        )

    public val self: ApiHttpHandler<PathSpec0, SUBJECT, Unit, SUBJECT> =
        path.path("self").get bind ApiHttpHandler(
            summary = "Get Self",
            auth = principal.auth(scopes = setOf(selfScope)),
            belongsToInterface = loggedInBelongsToInterface,
            inputType = Unit.serializer(),
            outputType = principal.subjectSerializer,
            implementation = { _ -> auth.fetch() }
        )

//    context(_: ServerRuntime)
//    public suspend fun presignToken(
//        session: Session<SUBJECT, ID>,
//        scopes: Set<GrantedScope> = GrantedScopes.root,
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
//        scopes: Set<GrantedScope> = GrantedScopes.root,
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