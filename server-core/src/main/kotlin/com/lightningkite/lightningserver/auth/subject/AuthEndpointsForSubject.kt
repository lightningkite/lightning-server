package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.oauth.OauthGrantTypes
import com.lightningkite.lightningserver.auth.oauth.OauthResponse
import com.lightningkite.lightningserver.auth.oauth.OauthTokenRequest
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.token.TokenFormat
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.encryption.checkHash
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.auth
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.SecureRandom
import java.util.*
import kotlin.math.min

class AuthEndpointsForSubject<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val handler: Authentication.SubjectHandler<SUBJECT, ID>,
    val database: () -> Database,
    val proofHasher: () -> SecureHasher,
    val tokenFormat: () -> TokenFormat,
) : ServerPathGroup(path) {

    init {
        prepareModels()
    }

    val sessionSerializer =  Session.serializer(handler.subjectSerializer, handler.idSerializer)

    val info = modelInfo<HasId<*>?, Session<SUBJECT, ID>, UUID>(
        modelName = "${handler.name}Session",
        serialization = ModelSerializationInfo(
            sessionSerializer,
            idSerializer = ContextualSerializer(UUID::class)
        ),
        authOptions = AuthOptions<SUBJECT>(setOf(AuthOption(handler.authType))) + Authentication.isSuperUser,
        getCollection = {
            database().collection(
                sessionSerializer,
                "${handler.name}Session"
            )
        },
        forUser = { collection: FieldCollection<Session<SUBJECT, ID>> ->
            val requestAuth = this.authOrNull
            val canUse: Condition<Session<SUBJECT, ID>> = when {
                Authentication.isSuperUser.accepts(requestAuth) -> Condition.Always()
                requestAuth == null -> Condition.Never()
                else -> Condition.OnField(
                    Session_subjectId(handler.subjectSerializer, handler.idSerializer),
                    Condition.Equal(requestAuth.rawId as ID)
                )
            }
            val isAdmin: Condition<Session<SUBJECT, ID>> =
                if (Authentication.isSuperUser.accepts(requestAuth)) Condition.Always() else Condition.Never()
            val path = DataClassPathSelf(sessionSerializer)
            collection.withPermissions(
                permissions = ModelPermissions(
                    create = isAdmin,
                    read = canUse,
                    readMask = Mask(listOf(Condition.Never<Session<SUBJECT, ID>>() to Modification.OnField(Session_secretHash(handler.subjectSerializer, handler.idSerializer), Modification.Assign("")))),
                    update = canUse,
                    updateRestrictions = UpdateRestrictions(
                        listOf(
                            UpdateRestrictions.Part(path._id, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.derivedFrom, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.secretHash, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.label, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.subjectId, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.createdAt, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.lastUsed, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.expires, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.terminated, canUse, Condition.Always()),
                            UpdateRestrictions.Part(path.ips, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.userAgents, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.scopes, isAdmin, Condition.Always()),
                            UpdateRestrictions.Part(path.oauthClient, isAdmin, Condition.Always()),
                        )
                    ),
                    delete = isAdmin,
                )
            )
        }
    )

    init {
        Authentication.readers += object : Authentication.Reader {
            override suspend fun request(request: Request): RequestAuth<*>? {
                try {
                    val token =
                        request.headers[HttpHeader.Authorization] ?: request.headers.cookies[HttpHeader.Authorization]
                        ?: return null
                    return tokenFormat().read(handler, token)
                        ?: RefreshToken(token).session()?.toAuth()
                } catch (e: TokenException) {
                    throw UnauthorizedException(e.message ?: "JWT issue")
                }
            }
        }
    }

    val errorNoSingleUser = LSError(
        404,
        detail = "no-single-user",
        message = "No single user could be found that matches the given credentials."
    )
    val errorInvalidProof = LSError(
        400,
        detail = "invalid-proof",
        message = "A given proof was invalid."
    )

    @Suppress("UNREACHABLE_CODE")
    val login = path("login").post.api(
        authOptions = noAuth,
        inputType = ListSerializer(Proof.serializer()),
        outputType = IdAndAuthMethods.serializer(handler.idSerializer),
        summary = "Log In",
        description = "Attempt to log in as a ${handler.name} using various proofs.  Valid proofs types are ${
            handler.idProofs.plus(
                handler.applicableProofs
            ).joinToString()
        }",
        errorCases = listOf(errorNoSingleUser, errorInvalidProof),
        implementation = { proofs: List<Proof> ->
            proofs.forEach {
                if (!proofHasher().verify(it)) throw HttpStatusException(errorInvalidProof)
            }
            val used = proofs.map { it.via }.toSet()
            val result =
                handler.authenticate(*proofs.toTypedArray()) ?: throw HttpStatusException(errorNoSingleUser)
            val strength = proofs.sumOf { it.strength }
            val maxStrengthPossible = result.options.sumOf { it.method.strength }
            val actStrenReq = min(result.strengthRequired, maxStrengthPossible)
            val secret = Base64.getEncoder().encodeToString(ByteArray(24) { 0 }.apply {
                SecureRandom.getInstanceStrong().nextBytes(this)
            })
            IdAndAuthMethods(
                session = if (strength >= actStrenReq) Session<SUBJECT, ID>(
                    secretHash = secret.secureHash(),
                    subjectId = result.id!!,
                    scopes = null,
                    label = "Root Session",
                    ips = setOf(),
                    userAgents = setOf(),
                ).also { info.collection().insertOne(it) }.let {
                    RefreshToken(it._id, secret).string
                } else null,
                id = result.id,
                options = result.options.filter { it.method.via !in used },
                strengthRequired = actStrenReq
            )
        }
    )

    val createSubSession = path("sub-session").post.api(
        authOptions = AuthOptions<SUBJECT>(setOf(AuthOption(handler.authType))),
        inputType = SubSessionRequest.serializer(),
        outputType = String.serializer(),
        summary = "Create Sub Session",
        description = "Creates a session with more limited authorization",
        errorCases = listOf(),
        implementation = { request: SubSessionRequest ->
            val secret = Base64.getEncoder().encodeToString(ByteArray(24) { 0 }.apply {
                SecureRandom.getInstanceStrong().nextBytes(this)
            })
            val session = Session<SUBJECT, ID>(
                secretHash = secret.secureHash(),
                label = request.label,
                subjectId = user()!!._id,
                derivedFrom = auth.sessionId,
                scopes = request.scopes,
                expires = request.expires,
                oauthClient = request.oauthClient,
                ips = setOf(),
                userAgents = setOf(),
            )
            info.collection().insertOne(session)
            RefreshToken(session._id, secret).string
        }
    )

    suspend fun Session<SUBJECT, ID>.toAuth(): RequestAuth<SUBJECT> = RequestAuth(
        subject = handler,
        rawId = this.subjectId,
        issuedAt = this.createdAt,
        scopes = this.scopes,
        sessionId = this._id,
        thirdParty = this.oauthClient
    ).withCachedValues(handler.knownCacheTypes)

    val token = path("token").post.api(
        authOptions = noAuth,
        summary = "Get Token",
        errorCases = listOf(),
        implementation = { input: OauthTokenRequest ->
            val session = when {
                input.refresh_token != null -> RefreshToken(input.refresh_token).session() ?: throw BadRequestException("Refresh token not recognized")
                else -> throw BadRequestException("No authentication provided")
            }
            val auth: RequestAuth<SUBJECT> = session.toAuth()
            when (input.grant_type) {
                OauthGrantTypes.refreshToken -> {
                    OauthResponse(
                        access_token = tokenFormat().create(handler, auth),
                        scope = auth.scopes?.joinToString(" ") ?: "*",
                        token_type = tokenFormat().type
                    )
                }
                OauthGrantTypes.authorizationCode -> TODO()
                else -> throw BadRequestException("Grant type ${input.grant_type} unsupported")
            }
        }
    )

    val sessions = ModelRestEndpoints(
        path = path("sessions"),
        info = info
    )

    private suspend fun RefreshToken.session(): Session<SUBJECT, ID>? {
        val session = info.collection().get(_id) ?: return null
        if(!plainTextSecret.checkHash(session.secretHash)) return null
        return session
    }
}

@JvmInline
private value class RefreshToken(val string: String) {
    constructor(_id: UUID, secret: String):this("$_id:$secret")
    val _id: UUID get() = UUID.fromString(string.substringBefore(':', ""))
    val plainTextSecret: String get() = string.substringAfter(':', "")
}
