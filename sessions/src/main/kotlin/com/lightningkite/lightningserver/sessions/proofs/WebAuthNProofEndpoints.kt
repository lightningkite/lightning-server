package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.auth.fetchUserIdString
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.database.*
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.*
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

public class WebAuthNProofEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    private val challengeLength: Int = 64,
    private val expiration: Duration = 5.minutes,
    private val rpId: () -> String,
    private val registrationForUser: (HasId<*>, WebAuthN.GeneralPreference) -> WebAuthN.Registration.RegistrationOptions,
    private val proveOptions: (String?) -> WebAuthN.Authentication.ProveOptions = { WebAuthN.Authentication.ProveOptions() },
) : ServerBuilder(), ProofMethod {

    init {
        proofMethods.register(this)
        path.docGroup = "WebAuthNProof"
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "WebAuthN",
        property = null,
        strength = 10
    )
    public val registerInterface: Documentable.OldInterfaceInfo = Documentable.OldInterfaceInfo("WebAuthNRegistrationEndpoints", listOf())
    public val proveInterface: Documentable.OldInterfaceInfo = Documentable.OldInterfaceInfo("WebAuthNProofEndpoints", listOf())


    context(_: ServerRuntime)
    private val active
        get() = condition<WebAuthNCredential> {
            it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gt(now()))
        }

    public val modelInfo: ModelInfo<HasId<AnyId>, WebAuthNCredential, String> = database.modelInfo(
        auth = proofMethodAuth or AuthRequirement.IsAdmin,
        permissions = {
            val admin = condition<WebAuthNCredential>(AuthRequirement.IsAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<WebAuthNCredential> {
                    it.subjectId.eq(a.rawId) and it.subjectType.eq(a.principalName)
                }
            } ?: Condition.Never
            ModelPermissions(
                create = Condition.Never,
                read = admin or mine,
                readMask = mask {
                    it.attestationObject.mask("", Condition.Never)
                    it.transports.mask(emptyList(), Condition.Never)
                },
                update = admin or (mine and active),
                updateRestrictions = updateRestrictions {
                    it.displayName.cannotBeModified()
                    it.establishedAt.cannotBeModified()
                    it.lastUsedAt.cannotBeModified()
                    it.subjectId.cannotBeModified()
                    it.subjectType.cannotBeModified()
                    it.residentKey.cannotBeModified()
                    it.authenticatorAttachment.cannotBeModified()
                    it.attestationObject.cannotBeModified()
                    it.transports.cannotBeModified()
                },
                delete = Condition.Never,
            )
        }
    )

    public val rest: ModelRestEndpoints<HasId<AnyId>, WebAuthNCredential, String> = path.path("credentials") include ModelRestEndpoints(modelInfo)


    private fun challengeCacheKey(key: String): String =
        "webAuthN_challenge_${key}"

    @Serializable
    public data class RegistrationCache(
        val challenge: String,
        val residentKeyPreference: WebAuthN.GeneralPreference,
        val allowedAlgorithms: List<WebAuthN.PublicKeyCredentialParameters>,
        val userVerification: Boolean,
    )

    @Serializable
    public data class AuthenticationCache(
        val challenge: String,
        val userVerification: Boolean,
        val subjectType: String,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun generate(): String {
        val bytes = ByteArray(challengeLength)
        SecureRandom().nextBytes(bytes)
        return WebAuthN.base64Encoder.encode(bytes)
    }

    context(server: ServerRuntime)
    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        principal: PrincipalType<SUBJECT, ID>,
        subject: SUBJECT,
    ): Boolean {
        return modelInfo.collection().findOne(condition {
            Condition.And(
                it.subjectId eq principal.idString(subject._id),
                it.subjectType eq principal.name,
                active
            )
        }) != null
    }

    context(server: ServerRuntime)
    private suspend fun userCredentials(subjectId: String, subjectType: String): List<WebAuthN.ExistingCredential> =
        modelInfo.collection()
            .find(condition {
                it.subjectId.eq(subjectId) and
                        it.subjectType.eq(subjectType) and
                        active
            })
            .map {
                WebAuthN.ExistingCredential(
                    id = it._id,
                    transports = it.transports.map { WebAuthN.Transport.fromStandardName(it) }
                )
            }
            .toList()

    @OptIn(ExperimentalEncodingApi::class)
    public val registerStart: ApiHttpHandler<PathSpec0, HasId<AnyId>, WebAuthN.GeneralPreference, WebAuthN.Registration.RegistrationResponse> =
        path.path("register-start").post bind ApiHttpHandler(
            auth = proofMethodAuth,
            belongsToInterface = registerInterface,
            summary = "Issue WebAuthN creation challenge",
            description = "Returns a challenge to be passed on to a client authenticator for the creation of a new Public Key Credential.",
            errorCases = listOf(),
            examples = listOf(),
            successCode = HttpStatus.OK,
            implementation = { residentKeyPreference: WebAuthN.GeneralPreference ->
                val options = registrationForUser(auth.fetch(), residentKeyPreference)

                val challenge = generate()
                val key = Uuid.random().toString()
                cache().set(
                    challengeCacheKey(key),
                    RegistrationCache(
                        challenge = challenge,
                        residentKeyPreference = residentKeyPreference,
                        allowedAlgorithms = options.pubKeyCredParams,
                        userVerification = options.authenticatorSelection.userVerification == WebAuthN.GeneralPreference.Required
                    )
                )

                WebAuthN.Registration.RegistrationResponse(
                    challengeId = key,
                    options = WebAuthN.Registration.PublicKeyCredentialCreationOptions(
                        attestation = options.attestation,
                        attestationFormats = options.attestationFormats,
                        authenticatorSelection = WebAuthN.Registration.AuthenticatorSelection(
                            authenticatorAttachment = options.authenticatorSelection.authenticatorAttachment,
                            residentKey = residentKeyPreference,
                            userVerification = options.authenticatorSelection.userVerification,
                        ),
                        challenge = challenge,
                        excludeCredentials = userCredentials(auth.rawId, auth.principalName),
                        extensions = options.extensions,
                        hints = options.hints,
                        pubKeyCredParams = options.pubKeyCredParams,
                        rp = WebAuthN.Registration.PublicKeyCredentialRpEntity(
                            id = rpId(),
                            name = generalSettings().projectName
                        ),
                        timeout = expiration.inWholeMilliseconds.toInt(),
                        user = options.user,
                    )
                )
            }
        )

    @OptIn(ExperimentalEncodingApi::class)
    public val registerFinish: ApiHttpHandler<PathSpec0, HasId<AnyId>, WebAuthN.Registration.RegisterRequest, Unit> =
        path.path("register-finish").post bind ApiHttpHandler(
            auth = proofMethodAuth,
            belongsToInterface = registerInterface,
            summary = "Establish WebAuthN Credential",
            description = "Validates and Accepts a public key credential created from a previously issued creation challenge.",
            errorCases = listOf(),
            examples = listOf(),
            successCode = HttpStatus.OK,
            implementation = { (challengeId, displayName, credentials): WebAuthN.Registration.RegisterRequest ->

                val clientData = serverRuntime.externalSerialization.json.decodeFromString<WebAuthN.ClientData>(
                    Base64.decode(credentials.response.clientDataJSON).decodeToString()
                )

                val cacheKey = challengeCacheKey(challengeId)
                val fromCache = cache().get<RegistrationCache>(cacheKey)
                    ?: throw BadRequestException("No Challenge available")
                cache().remove(cacheKey)

                if (fromCache.challenge != WebAuthN.base64Decoder.decode(clientData.challenge).decodeToString())
                    throw BadRequestException("No Challenge available")


                val data = RegistrationRequest(
                    WebAuthN.base64Decoder.decode(credentials.response.attestationObject),
                    WebAuthN.base64Decoder.decode(credentials.response.clientDataJSON),
                    serverRuntime.externalSerialization.json.encodeToString(credentials.clientExtensionResults),
                    credentials.response.transports.map { it.standardName }.toSet(),
                )

                val registrationParams: RegistrationParameters = RegistrationParameters(
                    /* serverProperty = */
                    ServerProperty(
                        /* origin = */ Origin(clientData.origin),
                        /* rpId = */ rpId(),
                        /* challenge = */ Challenge { fromCache.challenge.encodeToByteArray() }
                    ),
                    /* pubKeyCredParams = */
                    fromCache.allowedAlgorithms.map {
                        PublicKeyCredentialParameters(
                            PublicKeyCredentialType.create(it.type),
                            COSEAlgorithmIdentifier.create(it.alg.coseAlgorithmId.toLong())
                        )
                    },
                    /* userVerificationRequired = */ fromCache.userVerification,
                )

                val dataResult: RegistrationData = try {
                    WebAuthnManager.createNonStrictWebAuthnManager().verify(
                        data,
                        registrationParams,
                    )
                } catch (e: VerificationException) {
                    throw BadRequestException("Failed to verify Authenticator")
                }

                modelInfo.collection().insertOne(
                    WebAuthNCredential(
                        _id = credentials.id,
                        displayName = displayName,
                        subjectId = auth.rawId,
                        subjectType = auth.principalName,
                        residentKey = when (fromCache.residentKeyPreference) {
                            WebAuthN.GeneralPreference.Discouraged -> false
                            WebAuthN.GeneralPreference.Preferred -> {
                                credentials.clientExtensionResults?.credProps?.rk == true
                            }

                            WebAuthN.GeneralPreference.Required -> true
                        },
                        lastSignCount = dataResult.attestationObject?.authenticatorData?.signCount ?: 0,
                        authenticatorAttachment = credentials.authenticatorAttachment,
                        attestationObject = credentials.response.attestationObject,
                        transports = credentials.response.transports.map { it.standardName },
                        establishedAt = now()
                    )
                )
                Unit
            }
        )


    // The user may or may not identify themselves. If they do not, they expect their authenticator to have discoverable
    // keys. If they do, then we must return the subjects existing credential IDs. If a user hits this endpoint WITH
    // authentication, then they are re-authenticating, and we will return the existing credential ids regardless of
    // identity provided.
    public val start: ApiHttpHandler<PathSpec0, HasId<AnyId>?, Identification, WebAuthN.Authentication.StartResponse> =
        path.path("start").post bind ApiHttpHandler(
            auth = anyAuth or noAuth,
            belongsToInterface = proveInterface,
            summary = "Begin WebAuthN challenge",
            description = "Returns a challenge to be passed on to a client authenticator for signing.",
            errorCases = listOf(),
            examples = listOf(),
            successCode = HttpStatus.OK,
            implementation = { (subjectType, subjectProperty, value): Identification ->

                val handler = serverRuntime.server.principalTypes.values.find { it.name == subjectType }
                if (handler == null)
                    throw BadRequestException("Invalid Subject Type")

                if ((subjectProperty != null).xor(value != null))
                    throw BadRequestException("You must provide or ignore property and value together.")

                val subjectId = subjectProperty?.let { property ->
                    value?.let { value ->
                        val id = handler.fetchUserIdString(property, value)
                        if (id == null || authOrNull != null && id != authOrNull?.rawId)
                        // Something didn't add up properly. Return a valid looking useless response
                            return@ApiHttpHandler WebAuthN.Authentication.StartResponse(
                                challengeId = Uuid.random().toString(),
                                options = WebAuthN.Authentication.PublicKeyCredentialRequestOptions(
                                    allowCredentials = emptyList(),
                                    challenge = generate(),
                                    extensions = WebAuthN.Authentication.RequestExtensions(),
                                    hints = emptyList(),
                                    rpId = rpId(),
                                    timeout = expiration.inWholeMilliseconds.toInt(),
                                    userVerification = WebAuthN.GeneralPreference.Required,
                                )
                            )
                        id
                    }
                }

                val existingCreds = (subjectId ?: authOrNull?.rawId)
                    ?.let { userCredentials(subjectId = it, subjectType = subjectType) }
                    ?: emptyList()

                val options = proveOptions(subjectId)

                val challenge = generate()
                val key = Uuid.random().toString()

                cache().set(
                    key = challengeCacheKey(key),
                    value = AuthenticationCache(
                        challenge = challenge,
                        userVerification = options.userVerification == WebAuthN.GeneralPreference.Required,
                        subjectType = subjectType,
                    )
                )

                WebAuthN.Authentication.StartResponse(
                    challengeId = key,
                    options = WebAuthN.Authentication.PublicKeyCredentialRequestOptions(
                        allowCredentials = existingCreds,
                        challenge = challenge,
                        extensions = options.extensions,
                        hints = options.hints,
                        rpId = rpId(),
                        timeout = expiration.inWholeMilliseconds.toInt(),
                        userVerification = options.userVerification,
                    )
                )
            }
        )


    @OptIn(ExperimentalEncodingApi::class)
    public val prove: ApiHttpHandler<PathSpec0, HasId<AnyId>?, WebAuthN.Authentication.ProveRequest, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
            belongsToInterface = proveInterface,
            summary = "Prove WebAuthN ownership",
            description = "Returns a challenge to be passed on to a client authenticator for signing.",
            errorCases = listOf(),
            examples = listOf(),
            successCode = HttpStatus.OK,
            implementation = { (challengeId, credentials): WebAuthN.Authentication.ProveRequest ->

                val clientData = serverRuntime.externalSerialization.json.decodeFromString<WebAuthN.ClientData>(
                    Base64.decode(credentials.response.clientDataJSON).decodeToString()
                )

                val cacheKey = challengeCacheKey(challengeId)
                val fromCache = cache().get<AuthenticationCache>(cacheKey)
                    ?: throw BadRequestException("No Challenge available")
                cache().remove(cacheKey)

                if (fromCache.challenge != WebAuthN.base64Decoder.decode(clientData.challenge).decodeToString())
                    throw BadRequestException("No Challenge available")

                val publicKeyCredential: WebAuthNCredential = modelInfo.collection()
                    .find(condition { it._id.eq(credentials.id) and active })
                    .firstOrNull()
                    ?: throw ForbiddenException("Invalid Credential ID")

                val authRequest = AuthenticationRequest(
                    WebAuthN.base64Decoder.decode(credentials.id),
                    WebAuthN.base64Decoder.decode(credentials.response.authenticatorData),
                    WebAuthN.base64Decoder.decode(credentials.response.clientDataJSON),
                    WebAuthN.base64Decoder.decode(credentials.response.signature),
                )

                val attestation =
                    AttestationObjectConverter(ObjectConverter()).convert(publicKeyCredential.attestationObject)!!


                @Suppress("DEPRECATION")
                val authParams = AuthenticationParameters(
                    ServerProperty(
                        /* origin = */ Origin(clientData.origin),
                        /* rpId = */ rpId(),
                        /* challenge = */ Challenge { fromCache.challenge.encodeToByteArray() }
                    ),
                    AuthenticatorImpl(
                        attestation.authenticatorData.attestedCredentialData!!,
                        attestation.attestationStatement,
                        publicKeyCredential.lastSignCount
                    ),
                    fromCache.userVerification,
                )

                val authData = try {
                    WebAuthnManager.createNonStrictWebAuthnManager().verify(
                        /* authenticationRequest = */ authRequest,
                        /* authenticationParameters = */ authParams
                    )
                } catch (e: VerificationException) {
                    e.printStackTrace()
                    throw BadRequestException("Failed to verify Authenticator")
                }

                modelInfo.collection().updateOneById(
                    publicKeyCredential._id,
                    modification {
                        it.lastUsedAt assign now()
                        it.lastSignCount assign (authData.authenticatorData?.signCount ?: 0L)
                    }
                )

                proofSigner.await().makeProof(
                    info = if (authData.authenticatorData?.isFlagUV == true) info.copy(strength = 20) else info,
                    property = "${fromCache.subjectType}/_id",
                    value = publicKeyCredential.subjectId,
                    at = now()
                )
            }
        )
}