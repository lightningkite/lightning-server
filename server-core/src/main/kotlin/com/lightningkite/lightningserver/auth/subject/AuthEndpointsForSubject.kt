package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOption
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.oauth.OauthGrantTypes
import com.lightningkite.lightningserver.auth.oauth.OauthResponse
import com.lightningkite.lightningserver.auth.oauth.OauthTokenRequest
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.auth.subject.*
import com.lightningkite.lightningserver.auth.token.TokenFormat
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.encryption.JwtException
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.TokenException
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.min
import kotlin.reflect.KType

class AuthEndpointsForSubject<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val sessionType: KType,
    val handler: Authentication.SubjectHandler<SUBJECT, ID>,
    val database: () -> Database,
    val proofHasher: () -> SecureHasher,
    val tokenFormat: () -> TokenFormat,
) : ServerPathGroup(path) {

    val info = ModelInfo<Session<SUBJECT, ID>, String>(
        modelName = "${handler.subjectSerializer.descriptor.serialName} Session",
        serialization = ModelSerializationInfo(
            Session.serializer(handler.subjectSerializer, handler.idSerializer),
            idSerializer = String.serializer()
        ),
        authOptions = setOf(null),
        getCollection = {
            database().collection(
                sessionType,
                "SessionFor${handler.subjectSerializer.descriptor.serialName}"
            )
        },
        forUser = { requestAuth ->
            withPermissions(
                permissions = ModelPermissions(
                    create = Condition.Never(),
                    read = condition { if (requestAuth?.subject == handler) it.subjectId eq (requestAuth.rawId as ID) else Condition.Never() },
                    update = Condition.Never(),
                    delete = condition { if (requestAuth?.subject == handler) it.subjectId eq (requestAuth.rawId as ID) else Condition.Never() },
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
                        ?: info.collection().get(token)?.toAuth()
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
    val login = path.post.typed(
        authOptions = setOf(null),
        inputType = ListSerializer(Proof.serializer()),
        outputType = IdAndAuthMethods.serializer(handler.idSerializer),
        summary = "Log In",
        description = "Attempt to log in as a ${handler.name} using various proofs.  Valid proofs types are ${
            handler.idProofs.plus(
                handler.applicableProofs
            ).joinToString()
        }",
        errorCases = listOf(errorNoSingleUser, errorInvalidProof),
        implementation = { none: Unit, proofs: List<Proof> ->
            proofs.forEach {
                if (!proofHasher().verify(it)) throw HttpStatusException(errorInvalidProof)
            }
            val result =
                handler.authenticate(*proofs.toTypedArray()) ?: throw HttpStatusException(errorNoSingleUser)
            val strength = proofs.sumOf { it.strength }
            val maxStrengthPossible = result.options.sumOf { it.method.strength }
            IdAndAuthMethods(
                session = if (strength >= min(result.strengthRequired, maxStrengthPossible)) Session<SUBJECT, ID>(
                    subjectId = result.id!!,
                    scopes = null,
                    ips = setOf(),
                    userAgents = setOf(),
                ).also { info.collection().insertOne(it) }._id else null,
                id = result.id,
                options = result.options,
                strengthRequired = result.strengthRequired
            )
        }
    )

    val createSubSession = path.post.typed(
        authOptions = setOf(AuthOption(handler.authType)),
        inputType = SubSessionRequest.serializer(),
        outputType = String.serializer(),
        summary = "Create Sub Session",
        description = "Creates a session with more limited authorization",
        errorCases = listOf(),
        implementation = { user: SUBJECT, request: SubSessionRequest ->
            val session = Session<SUBJECT, ID>(
                label = request.label,
                subjectId = user._id,
                scopes = request.scopes,
                expires = request.expires,
                oauthClient = request.oauthClient,
                ips = setOf(),
                userAgents = setOf(),
            )
            info.collection().insertOne(session)
            session._id
        }
    )

    suspend fun Session<SUBJECT, ID>.toAuth(): RequestAuth<SUBJECT> = RequestAuth(
        subject = handler,
        rawId = this.subjectId,
        issuedAt = this.createdAt,
        scopes = this.scopes,
        thirdParty = this.oauthClient
    ).withCachedValues(handler.knownCacheTypes)

    val token = path.get.typed(
        summary = "Get Token",
        errorCases = listOf(),
        implementation = { _: Unit, input: OauthTokenRequest ->
            val session = when {
                input.refresh_token != null -> info.collection().get(input.refresh_token)
                    ?: throw BadRequestException("Refresh token not recognized")

                else -> throw BadRequestException("No authentication provided")
            }
            val auth: RequestAuth<SUBJECT> = session.toAuth()
            when (input.grant_type) {
                OauthGrantTypes.refreshToken -> {
                    OauthResponse(
                        access_token = tokenFormat().create(handler, auth),
                        refresh_token = session._id,
                        scope = auth.scopes?.joinToString(" ") ?: "*",
                        token_type = tokenFormat().type
                    )
                }

                OauthGrantTypes.authorizationCode -> TODO()
                else -> throw BadRequestException("Grant type ${input.grant_type} unsupported")
            }
        }
    )
}

