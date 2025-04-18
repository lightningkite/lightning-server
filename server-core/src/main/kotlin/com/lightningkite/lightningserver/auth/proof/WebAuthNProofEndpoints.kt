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
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningdb.mask
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.or
import com.lightningkite.lightningdb.updateOneById
import com.lightningkite.lightningdb.updateRestrictions
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.Authentication.ProofMethod
import com.lightningkite.lightningserver.auth.accepts
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
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
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
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class WebAuthNProofEndpoints<USER : HasId<*>>(
    path: ServerPath,
    val database: () -> Database,
    val cache: () -> Cache,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val challengeLength: Int = 64,
    val expiration: Duration = 5.minutes,
    val rpId: String? = null,
    val authOptions: AuthOptions<USER>,
    val registrationForUser: (USER) -> WebAuthNRegistrationOptions,
    val proveOptions: () -> WebAuthNProveOptions,
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

    val active get() = condition<WebAuthNCredential> { it.disabledAt.eq(null) }

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
                    it.authenticatorAttachment.mask("", Condition.Never)
                    it.clientExtensionResults.mask(emptyMap(), Condition.Never)
                    it.attestationObject.mask("", Condition.Never)
                    it.authenticatorData.mask("", Condition.Never)
                    it.clientDataJSON.mask("", Condition.Never)
                    it.publicKey.mask("", Condition.Never)
                    it.publicKeyAlgorithm.mask(0, Condition.Never)
                    it.transports.mask(emptyList(), Condition.Never)
                    it.disabledAt.mask(null, Condition.Never)
                },
                update = admin or (mine and active),
                updateRestrictions = updateRestrictions {
                    it.displayName.cannotBeModified()
                    it.establishedAt.cannotBeModified()
                    it.lastUsedAt.cannotBeModified()
                    it.subjectId.cannotBeModified()
                    it.authenticatorAttachment.cannotBeModified()
                    it.clientExtensionResults.cannotBeModified()
                    it.authenticatorAttachment.cannotBeModified()
                    it.clientExtensionResults.cannotBeModified()
                    it.attestationObject.cannotBeModified()
                    it.authenticatorData.cannotBeModified()
                    it.clientDataJSON.cannotBeModified()
                    it.publicKey.cannotBeModified()
                    it.publicKeyAlgorithm.cannotBeModified()
                    it.transports.cannotBeModified()
                    it.disabledAt.cannotBeModified()
                },
                delete = Condition.Never,
            )
        }
    )

    val rest = ModelRestEndpoints(path("credentials"), modelInfo)


    private fun challengeCacheKey(key: String): String =
        "webAuthN_challenge_${key}"


    data class ChallengeAndKey(val challenge: String, val key: String)

    suspend fun establishChallenge(): ChallengeAndKey {
        val challenge = generate()
        val key = UUID.random().toString()
        cache().set(challengeCacheKey(key), challenge, expiration)
        return ChallengeAndKey(challenge, key)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun generate(): String {
        val bytes = ByteArray(challengeLength)
        SecureRandom().nextBytes(bytes)
        return Base64.WebAuthNEncoder.encode(bytes)
    }

    suspend fun assert(key: String, challenge: String): String {
        val cacheKey = challengeCacheKey(key)
        val fromCache = cache().get<String>(cacheKey)
        cache().remove(cacheKey)

        if (fromCache != challenge)
            throw BadRequestException("No Challenge available")

        return fromCache
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

    suspend fun userCredentials(subjectId: String, subjectType: String): List<ExistingCredential> =
        modelInfo.collection()
            .find(condition {
                it.subjectId.eq(subjectId) and
                        it.subjectType.eq(subjectType) and
                        active
            })
            .map {
                ExistingCredential(
                    id = it._id,
                    transports = it.transports
                )
            }
            .toList()

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalEncodingApi::class)
    val registerStart = path("register-start").post.api(
        belongsToInterface = registerInterface,
        authOptions = authOptions,
        summary = "Issue WebAuthN creation challenge",
        description = "Returns a challenge to be passed on to a client authenticator for the creation of a new Public Key Credential.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { residentKeyPreference: GeneralPreference ->

            val challengeAndKey = establishChallenge()

            val options = registrationForUser(auth.get() as USER)

            WebAuthNRegistrationResponse(
                challengeId = challengeAndKey.key,
                options = PublicKeyCredentialCreationOptions(
                    attestation = options.attestation,
                    attestationFormats = options.attestationFormats,
                    authenticatorSelection = AuthenticatorSelection(
                        authenticatorAttachment = options.authenticatorSelection.authenticatorAttachment,
                        residentKey = residentKeyPreference,
                        userVerification = options.authenticatorSelection.userVerification,
                    ),
                    challenge = challengeAndKey.challenge,
                    excludeCredentials = userCredentials(auth.idString, auth.subject.name),
                    extensions = options.extensions,
                    hints = options.hints,
                    pubKeyCredParams = options.pubKeyCredParams,
                    rp = PublicKeyCredentialRpEntity(
                        id = rpId,
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
        authOptions = authOptions,
        summary = "Establish WebAuthN Credential",
        description = "Validates and Accepts a public key credential created from a previously issued creation challenge.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { (challengeId, displayName, credentials): WebAuthNRegisterFinish ->

            val clientData = Serialization.json.decodeFromString<ClientData>(
                Base64.decode(credentials.response.clientDataJSON).decodeToString()
            )
            val challengeFromCache =
                assert(challengeId, Base64.WebAuthNDecoder.decode(clientData.challenge).decodeToString())

            val manager = WebAuthnManager.createNonStrictWebAuthnManager()
            val data: RegistrationData =
                manager.parse(
                    RegistrationRequest(
                        Base64.WebAuthNDecoder.decode(credentials.response.attestationObject),
                        Base64.WebAuthNDecoder.decode(credentials.response.clientDataJSON),
                        null,
                        credentials.response.transports.map { it.jsonName }.toSet(),
                    )
                )
            manager.parseRegistrationResponseJSON(Serialization.json.encodeToString(credentials))

            val registrationParams = RegistrationParameters(
                ServerProperty(
                    Origin(clientData.origin),
                    rpId ?: "",
                    Challenge { challengeFromCache.encodeToByteArray() }),
                listOf(
                    com.webauthn4j.data.PublicKeyCredentialParameters(
                        PublicKeyCredentialType.PUBLIC_KEY,
                        COSEAlgorithmIdentifier.create(credentials.response.publicKeyAlgorithm.toLong())
                    )
                ),
                true,
            )

            try {
                WebAuthnManager.createNonStrictWebAuthnManager().verify(
                    data,
                    registrationParams,
                )
            } catch (e: VerificationException) {
                throw BadRequestException("Failed to verify Authenticator")
            }

            @Suppress("UNCHECKED_CAST")
            val credential = WebAuthNCredential(
                _id = credentials.id,
                displayName = displayName,
                subjectId = auth.idString,
                subjectType = (auth.subject as Authentication.SubjectHandler<HasId<Comparable<Comparable<*>>>, Comparable<Comparable<*>>>).name,
                authenticatorAttachment = credentials.authenticatorAttachment,
                clientExtensionResults = credentials.clientExtensionResults,
                attestationObject = credentials.response.attestationObject,
                authenticatorData = credentials.response.authenticatorData,
                clientDataJSON = credentials.response.clientDataJSON,
                publicKey = credentials.response.publicKey,
                publicKeyAlgorithm = credentials.response.publicKeyAlgorithm,
                transports = credentials.response.transports,
            )

            modelInfo.collection().insertOne(credential)
            Unit
        }
    )


    // At this point, the subject need not identify themselves. This is because the Public Key Credential that the client
    // authenticator uses to sign the challenge will be used in the "prove" step to determine the subject
    // that is signing in.
    val start = path("start").post.api(
        belongsToInterface = proveInterface,
        authOptions = noAuth,
        summary = "Begin WebAuthN challenge",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { (subjectId, subjectType): WebAuthNStart ->

            val challengeAndKey = establishChallenge()

            @Suppress("UNCHECKED_CAST")
            val options = proveOptions()

            WebAuthNStartResponse(
                challengeId = challengeAndKey.key,
                options = PublicKeyCredentialRequestOptions(
                    allowCredentials = subjectId?.let { id ->
                        subjectType?.let { type ->
                            userCredentials(subjectId = subjectId, subjectType = subjectType)
                        }
                    }
                        ?: emptyList(),
                    challenge = challengeAndKey.challenge,
                    extensions = options.extensions,
                    hints = options.hints,
                    rpId = rpId,
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
        implementation = { (challengeId, credentials): WebAuthNProve ->

            val clientData = Serialization.json.decodeFromString<ClientData>(
                Base64.decode(credentials.response.clientDataJSON).decodeToString()
            )
            val challengeFromCache =
                assert(challengeId, Base64.WebAuthNDecoder.decode(clientData.challenge).decodeToString())

            val publicKeyCredential: WebAuthNCredential = modelInfo.collection()
                .find(condition { it._id.eq(credentials.id) and active })
                .firstOrNull()
                ?: throw ForbiddenException("Invalid Credential ID")

            val manager = WebAuthnManager.createNonStrictWebAuthnManager()

            val authRequest = AuthenticationRequest(
                Base64.WebAuthNDecoder.decode(credentials.id),
                Base64.WebAuthNDecoder.decode(credentials.response.authenticatorData),
                Base64.WebAuthNDecoder.decode(credentials.response.clientDataJSON),
                Base64.WebAuthNDecoder.decode(credentials.response.signature),
            )

            val attestation =
                AttestationObjectConverter(ObjectConverter()).convert(publicKeyCredential.attestationObject)!!

            val authParams = AuthenticationParameters(
                ServerProperty(
                    /* origin = */ Origin(clientData.origin),
                    /* rpId = */ rpId ?: "",
                    /* challenge = */ Challenge { challengeFromCache.encodeToByteArray() }),
                AuthenticatorImpl(
                    attestation.authenticatorData.attestedCredentialData!!,
                    attestation.attestationStatement,
                    attestation.authenticatorData.signCount
                ),
                false
            )

            try {
                manager.validate(
                    authRequest,
                    authParams
                )
            } catch (e: VerificationException) {
                e.printStackTrace()
                throw BadRequestException("Failed to verify Authenticator")
            }

            modelInfo.collection().updateOneById(
                publicKeyCredential._id,
                modification { it.lastUsedAt assign now() }
            )

            proofHasher().makeProof(
                info = info,
                property = "_id",
                value = publicKeyCredential.subjectId,
                at = now()
            )
        }
    )
}