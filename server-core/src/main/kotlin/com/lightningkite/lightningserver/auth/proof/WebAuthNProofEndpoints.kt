package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.and
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.findOne
import com.lightningkite.lightningdb.gt
import com.lightningkite.lightningdb.gte
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.mask
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.or
import com.lightningkite.lightningdb.updateOneById
import com.lightningkite.lightningdb.updateRestrictions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.Authentication.ProofMethod
import com.lightningkite.lightningserver.auth.accepts
import com.lightningkite.lightningserver.auth.anyAuth
import com.lightningkite.lightningserver.auth.anyAuthRoot
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.plus
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.now
import com.lightningkite.serialization.notNull
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WebAuthNProofEndpoints(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val challengeLength: Int = 64,
    val expiration: Duration = 5.minutes,
    val rpId: () -> String,
    val registrationForUser: (HasId<*>, WebAuthN.GeneralPreference) -> WebAuthN.Registration.RegistrationOptions,
    val proveOptions: (String?) -> WebAuthN.Authentication.ProveOptions = { WebAuthN.Authentication.ProveOptions() },
) : ServerPathGroup(path), ProofMethod {

    init {
        path.docName = "WebAuthNProof"
    }

    override val info = ProofMethodInfo(
        via = "WebAuthN",
        property = null,
        strength = 10
    )

    init {
        Authentication.register(this)
    }

    val registerInterface = Documentable.InterfaceInfo(path, "WebAuthNRegistrationEndpoints", listOf())
    val proveInterface = Documentable.InterfaceInfo(path, "WebAuthNProofEndpoints", listOf())

    val active get() = condition<WebAuthNCredential> { it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gt(now())) }

    val modelInfo = database.modelInfo<HasId<*>?, WebAuthNCredential, String>(
        collectionName = "WebAuthNCredential",
        authOptions = anyAuthRoot + Authentication.isAdmin,
        permissions = {
            val admin = condition<WebAuthNCredential>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<WebAuthNCredential> {
                    it.subjectId.eq(a.idString) and it.subjectType.eq(a.subject.name)
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

    val rest = ModelRestEndpoints(path("credentials"), modelInfo)


    private fun challengeCacheKey(key: String): String =
        "webAuthN_challenge_${key}"

    @Serializable
    data class RegistrationCache(
        val challenge: String,
        val residentKeyPreference: WebAuthN.GeneralPreference,
        val allowedAlgorithms: List<WebAuthN.PublicKeyCredentialParameters>,
        val userVerification: Boolean,
    )

    @Serializable
    data class AuthenticationCache(
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

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.collection().findOne(condition {
            it.subjectId.eq(handler.idString(item._id)) and
                    it.subjectType.eq(handler.name) and
                    active
        }) != null
    }

    suspend fun userCredentials(subjectId: String, subjectType: String): List<WebAuthN.ExistingCredential> =
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
    val registerStart = path("register-start").post.api(
        belongsToInterface = registerInterface,
        authOptions = anyAuthRoot,
        summary = "Issue WebAuthN creation challenge",
        description = "Returns a challenge to be passed on to a client authenticator for the creation of a new Public Key Credential.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { residentKeyPreference: WebAuthN.GeneralPreference ->

            @Suppress("UNCHECKED_CAST")
            val options = registrationForUser(auth.get(), residentKeyPreference)

            val challenge = generate()
            val key = UUID.random().toString()
            cache().set(
                challengeCacheKey(key), RegistrationCache(
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
                    excludeCredentials = userCredentials(auth.idString, auth.subject.name),
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
    val registerFinish = path("register-finish").post.api(
        belongsToInterface = registerInterface,
        authOptions = anyAuthRoot,
        summary = "Establish WebAuthN Credential",
        description = "Validates and Accepts a public key credential created from a previously issued creation challenge.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { (challengeId, displayName, credentials): WebAuthN.Registration.RegisterRequest ->

            val clientData = Serialization.json.decodeFromString<WebAuthN.ClientData>(
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
                Serialization.json.encodeToString(credentials.clientExtensionResults),
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

            @Suppress("UNCHECKED_CAST")
            modelInfo.collection().insertOne(
                WebAuthNCredential(
                    _id = credentials.id,
                    displayName = displayName,
                    subjectId = auth.idString,
                    subjectType = (auth.subject as Authentication.SubjectHandler<HasId<Comparable<Comparable<*>>>, Comparable<Comparable<*>>>).name,
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
                )
            )
            Unit
        }
    )


    // The user may or may not identify themselves. If they do not, they expect their authenticator to have discoverable
    // keys. If they do, then we must return the subjects existing credential IDs. If a user hits this endpoint WITH
    // authentication, then they are re-authenticating, and we will return the existing credential ids regardless of
    // identity provided.
    val start = path("start").post.api(
        belongsToInterface = proveInterface,
        authOptions = anyAuth + noAuth,
        summary = "Begin WebAuthN challenge",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { (subjectType, subjectProperty, value): Identification ->

            val handler = Authentication.subjects.values.find { it.name == subjectType }
            if (handler == null)
                throw BadRequestException("Invalid Subject Type")

            if ((subjectProperty != null).xor(value != null))
                throw BadRequestException("You must provide or ignore property and value together.")

            val subjectId = subjectProperty?.let { property ->
                value?.let { value ->
                    val id = handler.findUserIdString(subjectProperty, value)
                    if (id == null || authOrNull != null && id != authOrNull.idString)
                        // Something didn't add up properly. Return a valid looking useless response
                        return@api WebAuthN.Authentication.StartResponse(
                            challengeId = UUID.random().toString(),
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

            val existingCreds = (subjectId ?: authOrNull?.idString)
                ?.let { userCredentials(subjectId = it, subjectType = subjectType) }
                ?: emptyList()

            val options = proveOptions(subjectId)

            val challenge = generate()
            val key = UUID.random().toString()

            cache().set(
                key = challengeCacheKey(key),
                value = AuthenticationCache(
                    challenge = challenge,
                    userVerification = options.userVerification == WebAuthN.GeneralPreference.Required,
                    subjectType = subjectType,
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
    val prove = path("prove").post.api(
        belongsToInterface = proveInterface,
        authOptions = noAuth,
        summary = "Prove WebAuthN ownership",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { (challengeId, credentials): WebAuthN.Authentication.ProveRequest ->

            val clientData = Serialization.json.decodeFromString<WebAuthN.ClientData>(
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

            proofHasher().makeProof(
                info = if (authData.authenticatorData?.isFlagUV == true) info.copy(strength = 20) else info,
                property = "${fromCache.subjectType}/_id",
                value = publicKeyCredential.subjectId,
                at = now()
            )
        }
    )
}