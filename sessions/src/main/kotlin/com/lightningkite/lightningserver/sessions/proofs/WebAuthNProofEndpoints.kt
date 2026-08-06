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
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.claimOnce
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.*
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

public class WebAuthNProofEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    override val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    override val proofExpiration: Duration = 1.hours,
    private val challengeLength: Int = 64,
    private val expiration: Duration = 5.minutes,
    private val rpId: context(ServerRuntime) () -> String,
    private val registrationForUser: context(ServerRuntime) (HasId<*>, WebAuthN.GeneralPreference) -> WebAuthN.Registration.RegistrationOptions,
    private val proveOptions: context(ServerRuntime) (String?) -> WebAuthN.Authentication.ProveOptions = { WebAuthN.Authentication.ProveOptions() },
) : ServerBuilder(), ProofMethod {
    init {
        proofMethodsRegistry.register(this)

        sdkSettings.defaultInfo = SdkModule.Info("WebAuthNProof", "webAuthN")
        sdkSettings.clientInterface = ProofClientEndpoints.WebAuthN::class.info()
    }

    override val info: ProofMethodInfo = ProofMethodInfo(
        via = "WebAuthN",
        property = null,
        strength = 10
    )

    context(_: ServerRuntime)
    private val active
        get() = condition<WebAuthNCredential> {
            it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gt(now()))
        }

    public val modelInfo: ModelInfo<HasId<*>, WebAuthNCredential, String> = database.modelInfo(
        tableName = "WebAuthNCredential",
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

    public val rest: ModelRestEndpoints<HasId<*>, WebAuthNCredential, String> =
        path.path("credentials") include ModelRestEndpoints(modelInfo)


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
        val allowCredentials: List<String>,
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
        return modelInfo.table().findOne(condition {
            Condition.And(
                it.subjectId eq principal.idString(subject._id),
                it.subjectType eq principal.name,
                active
            )
        }) != null
    }

    context(server: ServerRuntime)
    private suspend fun userCredentials(subjectId: String, subjectType: String): List<WebAuthN.ExistingCredential> =
        modelInfo.table()
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
    public val registerStart: ApiHttpHandler<PathSpec0, HasId<*>, WebAuthN.GeneralPreference, WebAuthN.Registration.RegistrationResponse> =
        path.path("register-start").post bind ApiHttpHandler(
            auth = proofMethodAuth,
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
                    ),
                    timeToLive = 15.minutes
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
    public val registerFinish: ApiHttpHandler<PathSpec0, HasId<*>, WebAuthN.Registration.RegisterRequest, Unit> =
        path.path("register-finish").post bind ApiHttpHandler(
            auth = proofMethodAuth,
            summary = "Establish WebAuthN Credential",
            description = "Validates and Accepts a public key credential created from a previously issued creation challenge.",
            errorCases = listOf(),
            examples = listOf(),
            successCode = HttpStatus.OK,
            implementation = { (challengeId, displayName, credential): WebAuthN.Registration.RegisterRequest ->

                val clientData = serverRuntime.externalSerialization.json.decodeFromString<WebAuthN.ClientData>(
                    Base64.decode(credential.response.clientDataJSON).decodeToString()
                )

                val cacheKey = challengeCacheKey(challengeId)
                val fromCache = cache().getAndRemove<RegistrationCache>(cacheKey)
                    ?: throw BadRequestException("No Challenge available")
                cache().remove(cacheKey)

                if (fromCache.challenge != WebAuthN.base64Decoder.decode(clientData.challenge).decodeToString())
                    throw BadRequestException("No Challenge available")


                val data = RegistrationRequest(
                    WebAuthN.base64Decoder.decode(credential.response.attestationObject),
                    WebAuthN.base64Decoder.decode(credential.response.clientDataJSON),
                    serverRuntime.externalSerialization.json.encodeToString(credential.clientExtensionResults),
                    credential.response.transports.map { it.standardName }.toSet(),
                )

                val registrationParams: RegistrationParameters = RegistrationParameters(
                    /* serverProperty = */
                    ServerProperty.builder()
                        .origin(Origin(clientData.origin))
                        .rpId(rpId())
                        .challenge { fromCache.challenge.encodeToByteArray() }
                        .build(),
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

                modelInfo.table().insertOne(
                    WebAuthNCredential(
                        _id = credential.id,
                        displayName = displayName,
                        subjectId = auth.rawId,
                        subjectType = auth.principalName,
                        residentKey = when (fromCache.residentKeyPreference) {
                            WebAuthN.GeneralPreference.Discouraged -> false
                            WebAuthN.GeneralPreference.Preferred -> {
                                credential.clientExtensionResults?.credProps?.rk == true
                            }

                            WebAuthN.GeneralPreference.Required -> true
                        },
                        lastSignCount = dataResult.attestationObject?.authenticatorData?.signCount ?: 0,
                        authenticatorAttachment = credential.authenticatorAttachment,
                        attestationObject = credential.response.attestationObject,
                        transports = credential.response.transports.map { it.standardName },
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
    public val start: ApiHttpHandler<PathSpec0, HasId<*>?, Identification, WebAuthN.Authentication.StartResponse> =
        path.path("start").post bind ApiHttpHandler(
            auth = anyAuth or noAuth,
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
                        val normalizedValue = handler.normalizePropertyValue(property, value)
                        val id = handler.fetchUserIdString(property, normalizedValue)
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
                        allowCredentials = existingCreds.map { it.id },
                    ),
                    timeToLive = 15.minutes
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
    public val prove: ApiHttpHandler<PathSpec0, HasId<*>?, WebAuthN.Authentication.ProveRequest, Proof> =
        path.path("prove").post bind ApiHttpHandler(
            auth = noAuth,
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
                val fromCache = cache().getAndRemove<AuthenticationCache>(cacheKey)
                    ?: throw BadRequestException("No Challenge available")
                cache().remove(cacheKey)

                if (fromCache.challenge != WebAuthN.base64Decoder.decode(clientData.challenge).decodeToString())
                    throw BadRequestException("No Challenge available")

                val publicKeyCredential: WebAuthNCredential = modelInfo.table()
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


                val authParams = AuthenticationParameters(
                    ServerProperty.builder()
                        .origin(Origin(clientData.origin))
                        .rpId(rpId())
                        .challenge { fromCache.challenge.encodeToByteArray() }
                        .build(),
                    CredentialRecordImpl(
                        /* attestationStatement = */ attestation.attestationStatement,
                        /* uvInitialized = */ null,
                        /* backupEligible = */ null,
                        /* backupState = */ null,
                        // Sign count anomaly detection is implemented in the webauthn4j library and is checked here.
                        /* counter = */ publicKeyCredential.lastSignCount,
                        /* attestedCredentialData = */ attestation.authenticatorData.attestedCredentialData!!,
                        /* authenticatorExtensions = */ null,
                        /* clientData = */ null,
                        /* clientExtensions = */ null,
                        /* transports = */ null,
                    ),
                    fromCache.allowCredentials.map { WebAuthN.base64Decoder.decode(it) }.takeIf { it.isNotEmpty() },
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

                // TODO(1.9, hardening audit): revisit sign-count rollback handling with the module's local
                //  expert. Today webauthn4j (createNonStrictWebAuthnManager) throws MaliciousCounterValueException
                //  on rollback only when sign-counts are nonzero; synced passkeys reset to 0 (so are exempt), and
                //  whether LS should add an explicit guard and Reject-vs-Flag policy needs more consideration
                //  (passkey lock-out risk, clone detection, multi-device). Deferred intentionally.
                modelInfo.table().updateOneById(
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
                )
            }
        )
}