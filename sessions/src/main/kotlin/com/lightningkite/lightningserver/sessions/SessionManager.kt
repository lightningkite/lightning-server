package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.AnyId
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.auth.auth
import com.lightningkite.lightningserver.auth.isSuperUser
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.encryption.sign
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.modelInfo2
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Mask
import com.lightningkite.services.database.ModelPermissions
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
    public val refreshHasher: RuntimeDeferred<Signer> = secretBasis.signer("refresh"),
    public val tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() }
) : ServerBuilder() {
    init { register(principal) }

    context(server: ServerRuntime)
    public open suspend fun sessionExpiration(subject: SUBJECT): Instant? = null

    context(server: ServerRuntime)
    public open suspend fun sessionStaleAfter(subject: SUBJECT): Duration? = null

    private val spath = Session.path(principal.subjectSerializer, principal.idSerializer)

    public val sessionInfo: ModelInfo<SUBJECT, ID, Session<SUBJECT, ID>, Uuid> =
        database.modelInfo2(
            authOptions = principal.auth(scopes = setOf("com/lightningkite/lightningserver/sessions")),
            serializer = Session.serializer(principal.subjectSerializer, principal.idSerializer),
            idSerializer = Uuid.serializer(),
            collectionName = principal.name + "Session",
            permissions = {
                val auth = this.authOrNull
                val canUse: Condition<Session<SUBJECT, ID>> = when {
                    auth == null -> Condition.Never
                    else -> spath.subjectId eq auth._id
                }

                val isRoot: Condition<Session<SUBJECT, ID>> =
                    if (AuthOptions.isSuperUser.accepts(auth)) Condition.Always
                    else Condition.Never

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

    context(_: ServerRuntime)
    protected suspend fun newSession(
        subjectId: ID,
        label: String? = null,
        expires: Instant? = null,
        stale: Instant? = null,
        scopes: Set<String>,
        oauthClient: String? = null,
        derivedFrom: Uuid? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        val secret = Base64.encode(CryptographyRandom.nextBytes(24))

        return Session<SUBJECT, ID>(
            secretHash = refreshHasher.await().sign(secret),
            subjectId = subjectId,
            label = label,
            expires = expires,
            stale = stale,
            limitTo = RequestPredicates(scopes = scopes),
            createdAt = now(),
            lastUsed = now(),
//            oauthClient = oauthClient,  TODO: OAuth
            derivedFrom = derivedFrom,
        ).also { sessionInfo.collection().insertOne(it) }.let {
            it to RefreshToken(principal.name, it._id, secret)
        }
    }

    context(_: ServerRuntime)
    protected suspend fun newSession(
        subjectId: ID,
        label: String? = null,
        expires: Instant? = null,
        stale: Instant? = null,
        limitTo: RequestPredicates? = null,
        forbid: RequestPredicates? = null,
        oauthClient: String? = null,
        derivedFrom: Uuid? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        val secret = Base64.encode(CryptographyRandom.nextBytes(24))

        return Session<SUBJECT, ID>(
            secretHash = refreshHasher.await().sign(secret),
            subjectId = subjectId,
            label = label,
            expires = expires,
            stale = stale,
            limitTo = limitTo,
            forbid = forbid,
            createdAt = now(),
            lastUsed = now(),
//            oauthClient = oauthClient,  TODO: OAuth
            derivedFrom = derivedFrom,
        ).also { sessionInfo.collection().insertOne(it) }.let {
            it to RefreshToken(principal.name, it._id, secret)
        }
    }

    context(server: ServerRuntime)
    public fun Session<SUBJECT, ID>.toAuth(): Authentication<SUBJECT, ID> = Authentication(
        principalType = principal,
        id = subjectId,
        sessionId = _id,
        issuedAt = createdAt,
        limitTo = limitTo,
        forbid = forbid
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
        if (!refreshHasher.await().verify(plainTextSecret, session.secretHash)) {
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

    public val tokenSimple: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, HasId<AnyId>?, AnyId, String, String>> =
        path.path("token").path("simple").post bind ApiHttpHandler(
            auth = noAuth,
            summary = "Get Token Simple",
            handler = { refresh: String ->
                val session = RefreshToken(refresh).session(request)
                    ?: throw BadRequestException("Refresh token not recognized")

                tokenFormat().create(principal, session.toAuth().apply { precache(principal.precache) })
            }
        )

    public val createSubSession: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, SUBJECT, ID, SubSessionRequest, String>> =
        path.path("sub-session").post bind ApiHttpHandler(
            auth = principal.auth(),
            inputType = SubSessionRequest.serializer(),
            outputType = String.serializer(),
            summary = "Create Sub Session",
            description = "Creates a session with more limited authorization",
            handler = { request: SubSessionRequest ->
                val session = sessionInfo.collection().get(this.auth.sessionId ?: throw UnauthorizedException())
                    ?: throw UnauthorizedException()

                newSession(
                    label = request.label,
                    subjectId = this.auth._id,
                    derivedFrom = this.auth.sessionId,
                    limitTo = request.limitTo,
                    forbid = request.forbid,
                    expires = session.expires
                        ?.let { minOf(it, request.expires ?: Instant.DISTANT_FUTURE) }
                        ?: request.expires,
                    stale = session.stale,
                    oauthClient = request.oauthClient,
                ).second.string
            }
        )

    public val self: Locationed<HttpEndpoint<PathSpec0>, ApiHttpHandler<PathSpec0, SUBJECT, ID, Unit, SUBJECT>> =
        path.path("self").get bind ApiHttpHandler(
            summary = "Get Self",
            auth = principal.auth(scopes = setOf("scopes")),
            inputType = Unit.serializer(),
            outputType = principal.subjectSerializer,
            handler = { _ -> this.auth.fetch() }
        )
}