package com.lightningkite.lightningserver.auth.subject

import com.lightningkite.UUID
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.HtmlDefaults
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.oauth.*
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.auth.proof.ProofEvidence
import com.lightningkite.lightningserver.auth.proof.verify
import com.lightningkite.lightningserver.auth.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.auth.token.TokenFormat
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.now
import io.ktor.http.*
import kotlinx.datetime.Instant
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.annotations.TestOnly
import java.security.SecureRandom
import java.util.*
import kotlin.math.min
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AuthEndpointsForSubject<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val handler: Authentication.SubjectHandler<SUBJECT, ID>,
    val database: () -> Database,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val tokenFormat: () -> TokenFormat = { PrivateTinyTokenFormat() },
) : ServerPathGroup(path), Authentication.Reader {

    init {
        prepareModels()
        if (path.docName == null) path.docName = "${handler.name}Auth"
    }

    private val sessionSerializer = Session.serializer(handler.subjectSerializer, handler.idSerializer)
    private val dataClassPath = DataClassPathSelf(sessionSerializer)

    val sessionInfo = modelInfo<HasId<*>?, Session<SUBJECT, ID>, UUID>(
        modelName = "${handler.name}Session",
        serialization = ModelSerializationInfo(
            sessionSerializer,
            idSerializer = ContextualSerializer(UUID::class)
        ),
        authOptions = AuthOptions<SUBJECT>(
            setOf(
                AuthOption(
                    handler.authType,
                    scopes = setOf("sessions")
                )
            )
        ) + Authentication.isSuperUser,
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
            collection.withPermissions(
                permissions = ModelPermissions(
                    create = isAdmin,
                    read = canUse,
                    readMask = Mask(
                        listOf(
                            Condition.Never<Session<SUBJECT, ID>>() to Modification.OnField(
                                Session_secretHash(handler.subjectSerializer, handler.idSerializer),
                                Modification.Assign("")
                            )
                        )
                    ),
                    update = isAdmin,
                    delete = isAdmin,
                )
            )
        }
    )

    init {
        Authentication.register(handler)
        Authentication.readers += this
    }

    override suspend fun request(request: Request): RequestAuth<*>? {
        // TODO: Read JWT from query params, remove and redirect
        try {
            val token =
                request.headers[HttpHeader.Authorization]?.removePrefix("bearer ")?.removePrefix("Bearer ")
                    ?: request.headers.cookies[HttpHeader.Authorization]
                    ?: return null
            return (tokenFormat().read(handler, token)
                ?: RefreshToken(token).session(request)?.toAuth())
        } catch (e: TokenException) {
            throw UnauthorizedException(e.message ?: "JWT issue")
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
    val errorExpiredProof = LSError(
        400,
        detail = "expired-proof",
        message = "A given proof expired."
    )

    @TestOnly
    suspend fun newSession(
        subjectId: ID,
        scopes: Set<String> = setOf("*"),
        label: String? = null,
        expires: Instant? = null,
        oauthClient: String? = null,
        derivedFrom: UUID? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> = newSessionPrivate(
        subjectId = subjectId,
        label = label,
        expires = expires,
        scopes = scopes,
        oauthClient = oauthClient,
        derivedFrom = derivedFrom
    )

    private suspend fun newSessionPrivate(
        subjectId: ID,
        label: String? = null,
        expires: Instant? = null,
        scopes: Set<String>,
        oauthClient: String? = null,
        derivedFrom: UUID? = null,
    ): Pair<Session<SUBJECT, ID>, RefreshToken> {
        val secret = Base64.getEncoder().encodeToString(ByteArray(24) { 0 }.apply {
            SecureRandom.getInstanceStrong().nextBytes(this)
        })
        return Session<SUBJECT, ID>(
            secretHash = secret.secureHash(),
            subjectId = subjectId,
            label = label,
            expires = expires,
            scopes = scopes,
            oauthClient = oauthClient,
            derivedFrom = derivedFrom,
        ).also { sessionInfo.collection().insertOne(it) }.let {
            it to RefreshToken(handler.name, it._id, secret)
        }
    }

    @Suppress("UNREACHABLE_CODE")
    val login = path("login").post.api(
        authOptions = noAuth,
        inputType = ListSerializer(Proof.serializer()),
        outputType = IdAndAuthMethods.serializer(handler.idSerializer),
        summary = "Log In",
        description = "Attempt to log in as a ${handler.name} using various proofs.  Valid proofs types are ${
            handler.idProofs.plus(
                handler.additionalProofs
            ).joinToString()
        }",
        errorCases = listOf(errorNoSingleUser, errorInvalidProof),
        implementation = { proofs: List<Proof> ->
            proofs.forEach {
                if (!proofHasher().verify(it)) throw HttpStatusException(errorInvalidProof.copy(data = it.via))
                if (now() > it.at + 1.hours) throw HttpStatusException(errorExpiredProof.copy(data = it.via))
            }
            val used = proofs.map { it.via }.toSet()
            val result =
                handler.authenticate(*proofs.toTypedArray()) ?: throw HttpStatusException(errorNoSingleUser)
            val strength = proofs.sumOf { it.strength }
            val maxStrengthPossible = result.options.sumOf { it.method.strength }
            val actStrenReq = min(result.strengthRequired, maxStrengthPossible)
            IdAndAuthMethods(
                session = if (strength >= actStrenReq) newSessionPrivate(
                    subjectId = result.id!!,
                    scopes = setOf("*"),
                    label = "Root Session",
                ).second.string else null,
                id = result.id,
                options = result.options.filter { it.method.via !in used },
                strengthRequired = actStrenReq
            )
        }
    )

    val openSession = path("open-session").post.api(
        authOptions = noAuth,
        summary = "Open Session",
        description = "Exchanges a future session token for a full session token.",
        inputType = String.serializer(),
        outputType = String.serializer(),
        errorCases = listOf(),
        implementation = { futureSessionToken: String ->
            val future = FutureSession.fromToken(futureSessionToken)
            if(future.oauthClient != null) throw ForbiddenException("Please use the token endpoint for OAuth instead, so we can check your secret.")
            val (s, secret) = newSessionPrivate(
                label = future.label,
                subjectId = future.subjectId,
                derivedFrom = future.originalSessionId,
                scopes = future.scopes,
                expires = null,
                oauthClient = future.oauthClient
            )
            sessionInfo.collection().insertOne(s)
            secret.string
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
            newSessionPrivate(
                label = request.label,
                subjectId = user()._id,
                derivedFrom = auth.sessionId,
                scopes = request.scopes,
                expires = request.expires,
                oauthClient = request.oauthClient,
            ).second.string
        }
    )

    private fun Session<SUBJECT, ID>.toAuth(): RequestAuth<SUBJECT> = RequestAuth(
        subject = handler,
        rawId = this.subjectId,
        issuedAt = this.createdAt,
        scopes = this.scopes,
        sessionId = this._id,
        thirdParty = this.oauthClient
    )

    val generateOauthCode = path("generate-oauth-code").get.api(
        authOptions = AuthOptions<SUBJECT>(setOf(AuthOption(handler.authType))),
        summary = "Generate Oauth Code",
        errorCases = listOf(),
        implementation = { input: OauthCodeRequest ->
            val client = OauthClientEndpoints.instance?.modelInfo?.collection()?.get(input.client_id)
                ?: throw BadRequestException("No client ID found")
            val baseUrl = input.redirect_uri.substringBefore('#').substringBefore('?')
            if (baseUrl !in client.redirectUris) throw BadRequestException("Redirect URI ${baseUrl} not valid.  Valid URIs: ${client.redirectUris.joinToString()}")
            OauthCode(
                code = FutureSession(
                    scopes = client.scopes intersect input.scope.split(' ').toSet(),
                    subjectId = auth.id,
                    oauthClient = client._id,
                    originalSessionId = auth.sessionId,
                ).asToken(),
                state = input.state
            )
        }
    )

    val token = path("token").post.api(
        authOptions = noAuth,
        summary = "Get Token",
        errorCases = listOf(),
        implementation = { input: OauthTokenRequest ->
            var generatedRefresh: RefreshToken? = null
            val session = when {
                input.refresh_token != null -> RefreshToken(input.refresh_token).session(
                    this.rawRequest
                ) ?: throw BadRequestException("Refresh token not recognized")

                input.code != null -> {
                    val client = OauthClientEndpoints.instance?.modelInfo?.collection()?.get(input.client_id)
                        ?: throw BadRequestException("Client ID/Secret mismatch")
                    if (client.secrets.none { input.client_secret.checkHash(it.secretHash) }) throw BadRequestException(
                        "Client ID/Secret mismatch"
                    )
                    val future = FutureSession.fromToken(input.code!!)
                    if (future.oauthClient != client._id) throw BadRequestException("Client/Token mismatch")
                    val (s, secret) = newSessionPrivate(
                        label = future.label ?: "Oauth with ${client.niceName}",
                        subjectId = future.subjectId,
                        derivedFrom = future.originalSessionId,
                        scopes = future.scopes,
                        expires = null,
                        oauthClient = future.oauthClient
                    )
                    sessionInfo.collection().insertOne(s)
                    generatedRefresh = secret
                    s
                }

                else -> throw BadRequestException("No authentication provided")
            }
            val auth: RequestAuth<SUBJECT> = session.toAuth().precache(handler.knownCacheTypes)
            when (input.grant_type) {
                OauthGrantTypes.refreshToken -> {
                    OauthResponse(
                        access_token = tokenFormat().create(handler, auth),
                        scope = auth.scopes?.joinToString(" ") ?: "*",
                        token_type = tokenFormat().type
                    )
                }

                OauthGrantTypes.authorizationCode -> {
                    OauthResponse(
                        access_token = tokenFormat().create(handler, auth),
                        scope = auth.scopes?.joinToString(" ") ?: "*",
                        token_type = tokenFormat().type,
                        refresh_token = generatedRefresh?.string
                    )
                }

                else -> throw BadRequestException("Grant type ${input.grant_type} unsupported")
            }
        }
    )

    val tokenSimple = path("token/simple").post.api(
        authOptions = noAuth,
        summary = "Get Token Simple",
        errorCases = listOf(),
        implementation = { refresh: String ->
            val session = RefreshToken(refresh).session(this.rawRequest ?: throw BadRequestException())
                ?: throw BadRequestException("Refresh token not recognized")
            tokenFormat().create(handler, session.toAuth().precache(handler.knownCacheTypes))
        }
    )

    val self = path("self").get.api(
        summary = "Get Self",
        authOptions = AuthOptions<SUBJECT>(setOf(AuthOption(handler.authType, scopes = setOf("self")))),
        errorCases = listOf(),
        inputType = Unit.serializer(),
        outputType = handler.subjectSerializer,
        implementation = { _ ->
            auth.get()
        }
    )

    suspend fun futureSessionToken(
        subjectId: ID,
        scopes: Set<String> = setOf("*"),
        label: String? = null,
        expires: Instant = now() + 5.minutes,
        oauthClient: String? = null,
        derivedFrom: UUID? = null,
    ): String = FutureSession(
        scopes = scopes,
        subjectId = subjectId,
        label = label,
        expires = expires,
        oauthClient = oauthClient,
        originalSessionId = derivedFrom,
    ).asToken()

    val sessions = ModelRestEndpoints(
        path = path("sessions"),
        info = sessionInfo
    )

    val sessionTerminate = sessions.path("terminate").post.api(
        authOptions = AuthOptions<SUBJECT>(setOf(AuthOption(handler.authType, scopes = null))),
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        summary = "Terminate Session",
        errorCases = listOf(),
        implementation = { _ ->
            sessionInfo.collection().updateOneById(this.auth.sessionId!!, modification(dataClassPath) {
                it.terminated assign now()
            })
        }
    )
    val otherSessionTerminate = sessions.path.arg<UUID>("sessionId").path("terminate").post.api(
        authOptions = AuthOptions<SUBJECT>(setOf(AuthOption(handler.authType, scopes = null))),
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        summary = "Terminate Other Session",
        errorCases = listOf(),
        implementation = { _ ->
            if (sessionInfo.collection().get(path1)?.subjectId != auth.id) throw ForbiddenException()
            sessionInfo.collection().updateOneById(path1, modification(dataClassPath) {
                it.terminated assign now()
            })
        }
    )

    private suspend fun RefreshToken.session(request: Request?): Session<SUBJECT, ID>? {
        if (!valid) return null
        if (type != handler.name) return null
        val session = sessionInfo.collection().get(_id) ?: return null
        if (!plainTextSecret.checkHash(session.secretHash)) return null
        if (session.terminated != null) return null
        sessionInfo.collection().updateOneById(_id, modification(dataClassPath) {
            it.lastUsed assign now()
            it.userAgents addAll setOf(request?.headers?.get(HttpHeader.UserAgent) ?: "")
            it.ips addAll setOf(request?.sourceIp ?: "test")
        })
        return session
    }

    private val hashSize by lazy { proofHasher().sign(byteArrayOf(1, 2, 3)).size }
    private fun FutureSession<ID>.asToken(): String = Base64.getEncoder().encodeToString(
        Serialization.javaData.encodeToByteArray(FutureSession.serializer(handler.idSerializer), this)
            .let { it + proofHasher().sign(it) })

    private fun FutureSession.Companion.fromToken(token: String): FutureSession<ID> =
        Base64.getDecoder().decode(token).let {
            val content = it.sliceArray(0 until it.size - hashSize)
            val signature = it.sliceArray(it.size - hashSize until it.size)
            if (!proofHasher().verify(content, signature)) throw TokenException("Could not verify hash.")
            Serialization.javaData.decodeFromByteArray(FutureSession.serializer(handler.idSerializer), content).also {
                if (now() > it.expires) throw TokenException("Token expired.")
            }
        }

    val oauthInfo by lazy {
        OauthProviderInfo(
            niceName = generalSettings().projectName,
            pathName = generalSettings().projectName.replace(' ', '-').lowercase(),
            identifierName = generalSettings().projectName.replace(' ', '-').lowercase(),
            loginUrl = "TODO",
            tokenUrl = token.path.path.fullUrl(),
            mode = OauthResponseMode.form_post,
            scopeForProfile = "self",
            getProfile = {
                val me = self.implementation(
                    AuthAndPathParts(
                        tokenFormat().read(handler, it.access_token)!!,
                        null,
                        arrayOf()
                    ), Unit
                )
                val json = Serialization.json.encodeToJsonElement(handler.subjectSerializer, me).jsonObject
                ExternalProfile(
                    email = json["email"]?.let { it as? JsonPrimitive }?.content,
                    username = json["username"]?.let { it as? JsonPrimitive }?.content
                        ?: json["screenName"]?.let { it as? JsonPrimitive }?.content
                        ?: json["email"]?.let { it as? JsonPrimitive }?.content,
                    name = json["name"]?.let { it as? JsonPrimitive }?.content
                        ?: json["fullName"]?.let { it as? JsonPrimitive }?.content
                        ?: json["firstName"]?.let { it as? JsonPrimitive }?.content,
                    image = json["image"]?.let { it as? JsonPrimitive }?.content
                        ?: json["profilePicture"]?.let { it as? JsonPrimitive }?.content,
                )
            }
        )
    }

    @Serializable
    private data class HtmlProofStartReq(val method: String, val value: String)
    @Serializable
    private data class HtmlProofFinish(val password: String)

    /**
     * A quick and dirty set of endpoints for logging in via HTML.
     */
    inner class HtmlEndpoints {

        // Raw HTML side
        val html0 = path("start/html/").get.handler { request ->
            val otherProofs = request.queryParameter("proofs")?.let { Serialization.javaData.decodeFromBase64Url<List<Proof>>(it) } ?: listOf()
            request.queryParameter("method")?.decodeURLPart()?.let { methodName ->
                request.queryParameter("value")?.decodeURLPart()?.let { methodValue ->
                    return@handler htmlContinue(HtmlProofStartReq(methodName, methodValue), request, otherProofs)
                }
            }
            HttpResponse(
                body = HttpContent.Text(
                    string = HtmlDefaults.basePage(
                        """
                    <form action='.?proofs=${Serialization.javaData.encodeToBase64Url(otherProofs)}' enctype='application/x-www-form-urlencoded' method='post'>
                        <p>Enter your login key</p>
                        <select name='method'>
                        ${handler.applicableProofs.joinToString() { "<option value='${it.name}'>${it.name}</option>" }}
                        </select>
                        <input type='value' name='value'/>
                        <button type='submit'>Submit</button>
                    </form>
                """.trimIndent()
                    ),
                    type = ContentType.Text.Html
                )
            )
        }

        val html1 = path("start/html/").post.handler { request ->
            val otherProofs = request.queryParameter("proofs")?.let { Serialization.javaData.decodeFromBase64Url<List<Proof>>(it) } ?: listOf()
            val input = request.body!!.parse<HtmlProofStartReq>()
            htmlContinue(input, request, otherProofs)
        }

        private suspend fun htmlContinue(
            input: HtmlProofStartReq,
            request: HttpRequest,
            otherProofs: List<Proof>
        ): HttpResponse {
            val method = handler.applicableProofs.find { it.name == input.method }
                ?: throw NotFoundException("No method ${input.method} known")
            val aapp = AuthAndPathParts<HasId<*>?, TypedServerPath0>(null, request, arrayOf())
            return when (method) {
                is Authentication.StartedProofMethod -> {
                    val key = method.start.implementation(aapp, input.value!!)
                    HttpResponse(
                        body = HttpContent.Text(
                            string = HtmlDefaults.basePage(
                                """
                        <form action='./${input.method}/${key.encodeURLPathPart()}?proofs=${Serialization.javaData.encodeToBase64Url(
                                    otherProofs
                                )}' enctype='application/x-www-form-urlencoded' method='post'>
                            <p>Enter your password for ${method.name}</p>
                            <input type='password' name='password'/>
                            <button type='submit'>Submit</button>
                        </form>
                    """.trimIndent()
                            ),
                            type = ContentType.Text.Html
                        )
                    )
                }

                is Authentication.DirectProofMethod -> {
                    HttpResponse(
                        body = HttpContent.Text(
                            string = HtmlDefaults.basePage(
                                """
                        <form action='./${input.method}/${input.value.encodeURLPathPart()}?proofs=${
                                    Serialization.javaData.encodeToBase64Url(
                                        otherProofs
                                    )
                                }' enctype='application/x-www-form-urlencoded' method='post'>
                            <p>Enter your password for ${method.name}</p>
                            <input type='password' name='password'/>
                            <button type='submit'>Submit</button>
                        </form>
                    """.trimIndent()
                            ),
                            type = ContentType.Text.Html
                        )
                    )
                }

                else ->
                    HttpResponse(
                        body = HttpContent.Text(
                            string = HtmlDefaults.basePage(
                                """
                                    Sorry, we do not now how to display this method for testing.
                                """.trimIndent()
                            ),
                            type = ContentType.Text.Html
                        )
                    )
            }
        }

        val html2 = path("start/html/{method}/{key}").post.handler { request ->
            val otherProofs = request.queryParameter("proofs")?.let { Serialization.javaData.decodeFromBase64Url<List<Proof>>(it) } ?: listOf()
            val methodName = request.parts["method"]!!
            val key = request.parts["key"]!!
            val input = request.body!!.parse<HtmlProofFinish>()
            val method = handler.applicableProofs.find { it.name == methodName }
                    as? Authentication.EndsWithStringProofMethod
                ?: throw NotFoundException("No method ${methodName} known")
            val aapp = AuthAndPathParts<HasId<*>?, TypedServerPath0>(null, request, arrayOf())
            val proof = method.prove.implementation(aapp, ProofEvidence(
                key = key!!,
                password = input.password
            ))
            val l = login.implementation(aapp, (otherProofs + proof))
            l.session?.let {
                HttpResponse.redirectToGet("/") {
                    setCookie(HttpHeader.Authorization, it)
                }
            } ?: run {
                val nextMethodInfo = l.options.first()
                HttpResponse.redirectToGet(html0.path.toString() + "?proofs=${Serialization.javaData.encodeToBase64Url(otherProofs + proof)}&method=${nextMethodInfo.method.via}&value=${nextMethodInfo.value}")
            }
        }
    }
    val html = HtmlEndpoints()
}

