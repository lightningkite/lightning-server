package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ModelPermissions
import com.lightningkite.lightningdb.and
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.gte
import com.lightningkite.lightningdb.insertOne
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
import com.lightningkite.now
import com.lightningkite.serialization.notNull
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
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
    val prefix: String,
    val expiration: Duration = 5.minutes,
    val rpId: String? = null,
    val registrationOptionsForUser: (USER) -> RegistrationOptions,
    val proveOptions: () -> ProveOptions,
    subjectAuthOptions: AuthOptions<USER>,
) : ServerPathGroup(path), ProofMethod {

    init {
        path.docName = "${prefix}WebAuthNProof"
    }

    override val info = ProofMethodInfo(
        via = "${prefix}WebAuthN",
        property = null,
        strength = 5
    )

    init {
        Authentication.register(this)
    }

    val loggedInInterfaceInfo = Documentable.InterfaceInfo(path, "AuthenticatedWebAuthNProofClientEndpoints", listOf())
    val interfaceInfo = Documentable.InterfaceInfo(path, "WebAuthNProofClientEndpoints", listOf())

    private val active
        get() = condition<WebAuthNCredential> {
            it.disabledAt.eq(null) and (it.expiresAt.eq(null) or it.expiresAt.notNull.gte(
                now()
            ))
        }

    val modelInfo = database.modelInfo<HasId<*>?, WebAuthNCredential, String>(
        collectionName = "",
        authOptions = anyAuthRoot + Authentication.isAdmin,
        permissions = {
            val admin = condition<WebAuthNCredential>(Authentication.isAdmin.accepts(authOrNull))
            val mine = authOrNull?.let { a ->
                condition<WebAuthNCredential> {
                    it.subjectId.eq(a.idString) and it.subjectName.eq(a.subject.name)
                }
            } ?: Condition.Never
            ModelPermissions(
                create = Condition.Never,
                read = admin or mine,
                update = admin or (mine and active),
                updateRestrictions = updateRestrictions {
                    it.subjectName.cannotBeModified()
                    it.subjectId.cannotBeModified()
                    it.publicKeyDerBase64.cannotBeModified()
                    it.algorithm.cannotBeModified()
                },
                delete = Condition.Never,
            )
        }
    )

    val rest = ModelRestEndpoints(path("credentials"), modelInfo)


    private fun challengeCacheKey(keyPrefix:String, uniqueIdentifier: String): String =
        "${keyPrefix}_challenge_${uniqueIdentifier}"


    data class ChallengeAndKey(val challenge: String, val key: String)

    suspend fun establishChallenge(keyPrefix:String): ChallengeAndKey {
        val challenge = generate()
        val key = UUID.random().toString()
        cache().set(challengeCacheKey(keyPrefix, key), challenge, expiration)
        return ChallengeAndKey(challenge, key)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun generate(): String {
        val bytes = ByteArray(challengeLength)
        SecureRandom().nextBytes(bytes)
        return Base64.WebAuthN.encode(bytes)
    }

    suspend fun assert(challenge: String, key:String, keyPrefix:String) {
        val cacheKey = challengeCacheKey(keyPrefix, key)
        val fromCache = cache().get<String>(cacheKey)
        cache().remove(cacheKey)

        if(fromCache != challenge)
            throw BadRequestException("No Challenge available")
    }



    @OptIn(ExperimentalEncodingApi::class)
    val registerStart = path("register-start").post.api(
        belongsToInterface = loggedInInterfaceInfo,
        authOptions = subjectAuthOptions,
        summary = "Issue WebAuthN register challenge",
        description = "Returns a challenge to be passed on to a client authenticator for the creation of a new Public Key Credential.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->

            val challengeAndKey = establishChallenge(prefix)

            val options = registrationOptionsForUser(auth.get())

            WebAuthNRegistrationResponse(
                challengeId = challengeAndKey.key,
                options = PublicKeyCredentialCreationOptions(
                    attestation = options.attestation,
                    attestationFormats = options.attestationFormats,
                    authenticatorSelection = options.authenticatorSelection,
                    challenge = challengeAndKey.challenge,
                    excludeCredentials = modelInfo.collection()
                        .find(condition {
                            it.subjectName.eq(auth.subject.name) and
                                    it.subjectId.eq(auth.idString) and
                                    active
                        })
                        .map { ExistingCredential(it._id) }
                        .toList(),
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
        belongsToInterface = loggedInInterfaceInfo,
        authOptions = subjectAuthOptions,
        summary = "Establish WebAuthN Credential",
        description = "Validates and Accepts a public key credential created from a previously issued creation challenge.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { request: RegisterFinishRequest ->

            val clientData = request.credential.response.clientData

            assert(clientData.challenge, auth.idString, prefix)

            val manager = WebAuthnManager.createNonStrictWebAuthnManager()
            val data: RegistrationData =
                manager.parseRegistrationResponseJSON(Serialization.json.encodeToString(request.credential))

            try {
                WebAuthnManager.createNonStrictWebAuthnManager().verify(
                    data,
                    RegistrationParameters(
                        ServerProperty(
                            Origin(clientData.origin),
                            rpId ?: "",
                            Challenge { clientData.challenge.encodeToByteArray() }),
                        listOf(
                            com.webauthn4j.data.PublicKeyCredentialParameters(
                                PublicKeyCredentialType.PUBLIC_KEY,
                                COSEAlgorithmIdentifier.create(request.credential.response.publicKeyAlgorithm.toLong())
                            )
                        ),
                        true,
                    ),
                )
            } catch (e: VerificationException) {
                throw BadRequestException("Failed to verify Authenticator")
            }

            val credential = WebAuthNCredential(
                _id = request.credential.id,
                subjectId = auth.idString,
                authenticatorAttachment = request.credential.authenticatorAttachment,
                clientExtensionResults = request.credential.clientExtensionResults,
                displayName = request.displayName,
                response = request.credential.response,
            )
            modelInfo.collection().insertOne(credential)
            Unit
        }
    )

    // At this point, the subject need not identify themselves. This is because the Public Key Credential that the client
    // authenticator uses to sign the challenge will be used in the "prove" step to determine the subject
    // that is signing in.
    val start = path("start").post.api(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Begin WebAuthN challenge",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->

            val challengeAndKey = establishChallenge(prefix)

            @Suppress("UNCHECKED_CAST")
            val options = proveOptions()

            WebAuthNStartResponse(
                challengeId = challengeAndKey.key,
                options = PublicKeyCredentialRequestOptions(
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
    val prove = path("prove").post.api<HasId<*>?, WebAuthNProveRequest, Proof>(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth + subjectAuthOptions,
        summary = "Prove WebAuthN ownership",
        description = "Returns a challenge to be passed on to a client authenticator for signing.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = { (key, credentials) ->

            val clientData = credentials.response.clientData
            assert(clientData.challenge, key, prefix)

            val publicKeyCredential: WebAuthNCredential = modelInfo.collection()
                .find(condition { it._id.eq(credentials.id) and active })
                .firstOrNull()
                ?: throw ForbiddenException("Invalid Credential ID")

            val manager = WebAuthnManager.createNonStrictWebAuthnManager()

            val authRequest = AuthenticationRequest(
                Base64.WebAuthN.decode(credentials.id),
                Base64.WebAuthN.decode(credentials.response.authenticatorData),
                Base64.WebAuthN.decode(credentials.response.clientDataJSON),
                Base64.WebAuthN.decode(credentials.response.signature),
            )

            val attestation =
                AttestationObjectConverter(ObjectConverter()).convert(publicKeyCredential.response.attestationObject)!!

            val authParams = AuthenticationParameters(
                ServerProperty(
                    Origin(clientData.origin),
                    rpId ?: "",
                    Challenge { clientData.challenge.encodeToByteArray() }),
                AuthenticatorImpl(
                    attestation.authenticatorData.attestedCredentialData!!,
                    attestation.attestationStatement,
                    attestation.authenticatorData.signCount
                ),
                true
            )

            try {
                manager.validate(
                    authRequest,
                    authParams
                )
            } catch (e: VerificationException) {
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

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        return modelInfo.collection().count(condition {
            it.subjectId.eq(handler.idString(item._id)) and
                    it.subjectName.eq(handler.name) and
                    active
        }) > 0
    }
}