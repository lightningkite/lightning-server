package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.reportAuthEvent
import com.lightningkite.lightningserver.sessions.AuthEvent
import com.lightningkite.lightningserver.BadRequestException
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
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Provides WebAuthn (passkey) registration and authentication as a [ProofMethod].
 *
 * A subject adds a passkey with [registerStart] and [registerFinish], which require a recent proof, then logs in with [start] and [prove] and exchanges the resulting [Proof] at `AuthEndpoints.login`. [info] has a strength of 10, and [prove] issues 20 when the authenticator verified the user, so requiring more than 10 demands verified presence.
 *
 * [start] never looks a subject up from a bare identifier, so it does not reveal which accounts have passkeys. Without a proof it starts the discoverable flow, and with a proof it returns that subject's credential ids. A subject with only non-discoverable credentials must therefore authenticate with another method first.
 *
 * @param rpId The relying party id the credentials are scoped to, typically your registrable domain.
 * @param allowedOrigins The exact origins permitted to create and use these credentials, such as `https://app.example.com`. This must be an explicit allowlist, since browsers produce assertions for [rpId] from any subdomain. Native apps need their own form: `https://<rpId>` on iOS, and `android:apk-key-hash:<base64url SHA-256 of the signing certificate>` on Android, which differs per signing key. Each also requires its platform's association file to be served from the [rpId] domain.
 * @param expiration How long an issued challenge stays usable, both as the `timeout` sent to the client and as its cache lifetime.
 * @param registrationForUser Supplies the creation options for [registerStart]. The client's resident-key preference is only a hint, so return it in [WebAuthN.AuthenticatorSelectionOptions.residentKey] to honor it.
 * @param proveOptions Supplies the assertion options for [start], given the subject id resolved from the request, or `null` for the discoverable flow.
 * @param webAuthnManager The verifier for registration and authentication. The default performs no attestation verification, so requesting [WebAuthN.Attestation.Direct] or [WebAuthN.Attestation.Enterprise] has no effect. Supply a strict [WebAuthnManager] to enforce an authenticator allowlist.
 */
public class WebAuthNProofEndpoints(
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    override val proofSigner: RuntimeDeferred<Signer> = secretBasis.signer("proof"),
    override val proofExpiration: Duration = 1.hours,
    private val challengeLength: Int = 64,
    private val expiration: Duration = 5.minutes,
    private val rpId: context(ServerRuntime) () -> String,
    private val allowedOrigins: context(ServerRuntime) () -> Set<String>,
    private val registrationForUser: context(ServerRuntime) (HasId<*>, WebAuthN.GeneralPreference) -> WebAuthN.Registration.RegistrationOptions,
    private val proveOptions: context(ServerRuntime) (String?) -> WebAuthN.Authentication.ProveOptions = { WebAuthN.Authentication.ProveOptions() },
    private val webAuthnManager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(),
) : ServerBuilder(), ProofMethod {
    init {
        proofMethodsRegistry.register(this)

        sdkSettings.defaultInfo = SdkModule.Info("WebAuthNProof", "webAuthN")
        sdkSettings.clientInterface = ProofClientEndpoints.WebAuthN::class.info()
    }

    /** Identifies this method to `AuthEndpoints` and to clients choosing a login option, with strength 10. */
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

    /**
     * Describes the [WebAuthNCredential] table and its permissions.
     *
     * Credentials cannot be created or deleted through it, so a subject revokes a passkey by setting `disabledAt`. A subject may read their own credentials and an administrator any, with `attestationObject` and `transports` masked. Only `disabledAt` is writable by the subject, and only an administrator may set `expiresAt`.
     */
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
                updateRestrictions = whitelistRestrictions {
                    it.disabledAt.canBeModified()
                    it.expiresAt requires admin
                },
                delete = Condition.Never,
            )
        }
    )

    /** Provides REST endpoints at `/credentials` for a subject to list and revoke their stored passkeys, governed by [modelInfo]. */
    public val rest: ModelRestEndpoints<HasId<*>, WebAuthNCredential, String> =
        path.path("credentials") include ModelRestEndpoints(modelInfo)


    private fun challengeCacheKey(key: String): String =
        "webAuthN_challenge_${key}"

    /** Holds the cached state of an in-flight [registerStart] challenge until [registerFinish] consumes it. */
    @Serializable
    public data class RegistrationCache(
        val challenge: String,
        val residentKeyPreference: WebAuthN.GeneralPreference,
        val allowedAlgorithms: List<WebAuthN.PublicKeyCredentialParameters>,
        val userVerification: Boolean,
    )

    /**
     * Holds the cached state of an in-flight [start] challenge until [prove] consumes it.
     *
     * [subjectType] limits which credentials [prove] accepts, and an empty [allowCredentials] means the discoverable flow, where any credential of that type is acceptable.
     */
    @Serializable
    public data class AuthenticationCache(
        val challenge: String,
        val userVerification: Boolean,
        val subjectType: String,
        val allowCredentials: List<String>,
    )

    /** Returns a base64url challenge of [challengeLength] random bytes from [SecureRandom]. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun generate(): String {
        val bytes = ByteArray(challengeLength)
        SecureRandom().nextBytes(bytes)
        return WebAuthN.base64Encoder.encode(bytes)
    }

    /**
     * Returns `true` if [subject] has a passkey that is neither disabled nor expired, so `AuthEndpoints` should offer this method.
     *
     * Only credentials registered under this [principal]'s type count.
     */
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

    /** Returns the subject's usable credentials in the shape the browser expects for `excludeCredentials` or `allowCredentials`. */
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

    /**
     * Issues a challenge for creating a passkey on the calling subject's account, requiring [proofMethodAuth].
     *
     * The request is the client's resident-key preference, which is only a hint passed to [registrationForUser]. The response holds a `challengeId` for [registerFinish], valid for [expiration], and the options for `navigator.credentials.create()`, with `excludeCredentials` listing the subject's existing credentials.
     */
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
                // The server decides residency; the request body is only the hint passed above.
                val residentKey = options.authenticatorSelection.residentKey
                val challenge = generate()
                val key = Uuid.random().toString()
                cache().set(
                    challengeCacheKey(key),
                    RegistrationCache(
                        challenge = challenge,
                        residentKeyPreference = residentKey,
                        allowedAlgorithms = options.pubKeyCredParams,
                        userVerification = options.authenticatorSelection.userVerification == WebAuthN.GeneralPreference.Required
                    ),
                    timeToLive = expiration
                )

                WebAuthN.Registration.RegistrationResponse(
                    challengeId = key,
                    options = WebAuthN.Registration.PublicKeyCredentialCreationOptions(
                        attestation = options.attestation,
                        attestationFormats = options.attestationFormats,
                        authenticatorSelection = WebAuthN.Registration.AuthenticatorSelection(
                            authenticatorAttachment = options.authenticatorSelection.authenticatorAttachment,
                            residentKey = residentKey,
                            userVerification = options.authenticatorSelection.userVerification,
                        ),
                        challenge = challenge,
                        excludeCredentials = userCredentials(auth.rawId, auth.principalName),
                        // Ask the browser to report whether it created a discoverable credential, unless the
                        // app configured credProps itself. Without the request, no report comes back at all.
                        extensions = options.extensions.let { if (it.credProps == null) it.copy(credProps = true) else it },
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

    /**
     * Verifies a newly created credential and stores it for the calling subject, requiring [proofMethodAuth].
     *
     * Fails with 400 if the challenge is unknown, used, expired or mismatched, the request is cross-origin, webauthn4j rejects the attestation, or the credential id differs from the attested one.
     */
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
                    WebAuthN.base64Decoder.decode(credential.response.clientDataJSON).decodeToString()
                )

                // webauthn4j does not check the cross-origin flag on either path, so reject it here:
                // an assertion produced inside a cross-origin iframe is not a first-party user gesture.
                if (clientData.crossOrigin == true)
                    throw BadRequestException("Cross-origin WebAuthN requests are not permitted")

                val cacheKey = challengeCacheKey(challengeId)
                val fromCache = cache().getAndRemove<RegistrationCache>(cacheKey)
                    ?: throw BadRequestException("No Challenge available")

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
                        .origins(allowedOrigins().mapTo(HashSet()) { Origin(it) })
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
                    webAuthnManager.verify(
                        data,
                        registrationParams,
                    )
                } catch (e: VerificationException) {
                    throw BadRequestException("Failed to verify Authenticator")
                }

                // WebAuthn L3 s7.1 step 24: the credential id stored MUST be the one the authenticator attested
                // to, not the one the client claimed. webauthn4j will not catch a divergence -- it only checks
                // credential ids against allowCredentials, never against the credential record -- so an
                // unchecked client-supplied id becomes this table's primary key. That lets a caller squat an
                // arbitrary _id (blocking the rightful registration of that credential on a duplicate key) and
                // silently breaks excludeCredentials and allowCredentials, since both would then advertise an id
                // the authenticator does not recognise.
                val attestedCredentialId = dataResult.attestationObject
                    ?.authenticatorData
                    ?.attestedCredentialData
                    ?.credentialId
                    ?: throw BadRequestException("Failed to verify Authenticator")
                if (!WebAuthN.base64Decoder.decode(credential.id).contentEquals(attestedCredentialId))
                    throw BadRequestException("Credential ID does not match the attested credential")

                // BE and BS are covered by the authenticator's signature, unlike anything the client reports.
                val attestedFlags = dataResult.attestationObject?.authenticatorData
                val backupEligible = attestedFlags?.isFlagBE == true

                modelInfo.table().insertOne(
                    WebAuthNCredential(
                        _id = credential.id,
                        displayName = displayName,
                        subjectId = auth.rawId,
                        subjectType = auth.principalName,
                        // Best effort; WebAuthn has no signed residency flag. Prefer the client's credProps
                        // report. Otherwise infer it: a backup-eligible (synced) credential is discoverable in
                        // practice, and a browser refuses to register a non-discoverable one when it is
                        // Required. "Discouraged" alone proves nothing, since passkey providers often create
                        // discoverable credentials regardless.
                        residentKey = credential.clientExtensionResults?.credProps?.rk
                            ?: (backupEligible || fromCache.residentKeyPreference == WebAuthN.GeneralPreference.Required),
                        lastSignCount = dataResult.attestationObject?.authenticatorData?.signCount ?: 0,
                        authenticatorAttachment = credential.authenticatorAttachment,
                        attestationObject = credential.response.attestationObject,
                        transports = credential.response.transports.map { it.standardName },
                        establishedAt = now(),
                        backupEligible = backupEligible,
                        backupState = attestedFlags?.isFlagBS,
                    )
                )
                Unit
            }
        )


    /**
     * Issues a challenge for signing in with an existing passkey.
     *
     * Authentication is optional. An authenticated caller is re-authenticating and receives their own credential ids, while an unauthenticated caller starts the discoverable flow, or receives the credential ids of the subject named by the optional [Proof] in the request. Fails with 400 if the subject type is unknown, or the proof is invalid, names no subject of that type, or belongs to someone other than the authenticated caller.
     */
    public val start: ApiHttpHandler<PathSpec0, HasId<*>?, WebAuthN.Authentication.StartRequest, WebAuthN.Authentication.StartResponse> =
        path.path("start").post bind ApiHttpHandler(
            auth = anyAuth or noAuth,
            summary = "Begin WebAuthN challenge",
            description = "Returns a challenge to be passed on to a client authenticator for signing.",
            errorCases = listOf(),
            examples = listOf(),
            successCode = HttpStatus.OK,
            implementation = { (subjectType, proof): WebAuthN.Authentication.StartRequest ->

                val handler = serverRuntime.server.principalTypes.values.find { it.name == subjectType }
                if (handler == null)
                    throw BadRequestException("Invalid Subject Type")

                // An authenticated caller of this type is re-authenticating and may see their own credentials.
                // A caller authenticated as a different principal type is treated as unauthenticated here.
                val selfId = authOrNull?.takeIf { it.principalName == subjectType }?.rawId

                // Credential ids (and their count and transports) are only released against a valid proof
                // from another method, never against a bare identifier. A signed proof cannot be produced
                // for an account the caller does not control, so this endpoint cannot be used to test
                // whether an account exists, and the errors below can safely be specific.
                val subjectId = proof?.let {
                    val method = serverRuntime.proofMethods.find { m -> m.info.via == it.via }
                    if (method == null || !method.isValid(it))
                        throw BadRequestException("Invalid proof")
                    val id = handler.fetchUserIdString(it.property, handler.normalizePropertyValue(it.property, it.value))
                        ?: throw BadRequestException("Proof does not identify a $subjectType")
                    if (authOrNull != null && selfId != id)
                        throw BadRequestException("Proof does not match the authenticated subject")
                    id
                } ?: selfId

                val existingCreds = subjectId
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
                    timeToLive = expiration
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


    /**
     * Verifies an assertion and returns the [Proof] that `AuthEndpoints.login` exchanges for a session, requiring no authentication.
     *
     * The proof names the subject type and id of the stored credential, not anything the caller supplied. Fails with 400 if the request is cross-origin or the challenge is unavailable, or with one indistinguishable error for an unknown credential, a credential of another subject type, or a bad signature.
     */
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
                    WebAuthN.base64Decoder.decode(credentials.response.clientDataJSON).decodeToString()
                )

                if (clientData.crossOrigin == true)
                    throw BadRequestException("Cross-origin WebAuthN requests are not permitted")

                val cacheKey = challengeCacheKey(challengeId)
                val fromCache = cache().getAndRemove<AuthenticationCache>(cacheKey)
                    ?: run {
                        // The challenge is single-use and time-limited, so an unknown id is one that
                        // expired, was already spent, or was never issued. No subject is known yet.
                        serverRuntime.reportAuthEvent(AuthEvent.ProofRejected(info, AuthEvent.ProofRejected.Reason.NoSuchChallenge, request = request))
                        throw BadRequestException("No Challenge available")
                    }

                if (fromCache.challenge != WebAuthN.base64Decoder.decode(clientData.challenge).decodeToString()) {
                    serverRuntime.reportAuthEvent(AuthEvent.ProofRejected(info, AuthEvent.ProofRejected.Reason.NoSuchChallenge, request = request))
                    throw BadRequestException("No Challenge available")
                }

                // The credential MUST be scoped to the principal type the challenge was issued for, and the
                // issued proof MUST name that credential's own type. Resolving the credential by id alone and
                // then labelling the proof with the caller-supplied type lets someone register a passkey as a
                // low-privilege principal type, ask for a challenge under a privileged one, and receive a proof
                // asserting "<PrivilegedType>/_id = <their own id>" -- which AuthEndpoints.login will honour
                // wherever the two types share an id space.
                val publicKeyCredential: WebAuthNCredential = modelInfo.table()
                    .find(condition {
                        it._id.eq(credentials.id) and
                                it.subjectType.eq(fromCache.subjectType) and
                                active
                    })
                    .firstOrNull()
                    ?: run {
                        // The credential id is what identifies the account here, so an unknown one
                        // resolves no subject.
                        serverRuntime.reportAuthEvent(AuthEvent.ProofRejected(info, AuthEvent.ProofRejected.Reason.NoSuchSubject, request = request))
                        // Deliberately the same opaque error the signature check below raises: an unknown credential
                        // id, a credential belonging to another subject, and a bad signature must not be told apart.
                        throw BadRequestException("Failed to verify Authenticator")
                    }

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
                        .origins(allowedOrigins().mapTo(HashSet()) { Origin(it) })
                        .rpId(rpId())
                        .challenge { fromCache.challenge.encodeToByteArray() }
                        .build(),
                    CredentialRecordImpl(
                        /* attestationStatement = */ attestation.attestationStatement,
                        /* uvInitialized = */ null,
                        // Null (credentials registered before these were recorded) skips webauthn4j's check.
                        /* backupEligible = */ publicKeyCredential.backupEligible,
                        /* backupState = */ publicKeyCredential.backupState,
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
                    webAuthnManager.verify(
                        /* authenticationRequest = */ authRequest,
                        /* authenticationParameters = */ authParams
                    )
                } catch (e: VerificationException) {
                    serverRuntime.reportAuthEvent(AuthEvent.ProofCredentialRejected(
                        info,
                        AuthEvent.ProofCredentialRejected.Reason.SecretMismatch,
                        principal = publicKeyCredential.subjectId,
                        request = request,
                    ))
                    throw BadRequestException("Failed to verify Authenticator")
                }

                // TODO(1.9, hardening audit): revisit sign-count rollback handling with the module's local
                //  expert. Today webauthn4j (the default non-strict manager) throws MaliciousCounterValueException
                //  on rollback only when sign-counts are nonzero; synced passkeys reset to 0 (so are exempt), and
                //  whether LS should add an explicit guard and Reject-vs-Flag policy needs more consideration
                //  (passkey lock-out risk, clone detection, multi-device). Deferred intentionally.
                modelInfo.table().updateOneById(
                    publicKeyCredential._id,
                    modification {
                        it.lastUsedAt assign now()
                        it.lastSignCount assign (authData.authenticatorData?.signCount ?: 0L)
                        authData.authenticatorData?.let { flags ->
                            // BS may legitimately change (e.g. once a passkey syncs). BE may not, so it is only
                            // filled in for credentials registered before it was recorded.
                            it.backupState assign flags.isFlagBS
                            if (publicKeyCredential.backupEligible == null)
                                it.backupEligible assign flags.isFlagBE
                        }
                    }
                )

                serverRuntime.reportAuthEvent(AuthEvent.ProofAccepted(info, principal = publicKeyCredential.subjectId, request = request))
                proofSigner.await().makeProof(
                    info = if (authData.authenticatorData?.isFlagUV == true) info.copy(strength = 20) else info,
                    property = "${publicKeyCredential.subjectType}/_id",
                    value = publicKeyCredential.subjectId,
                )
            }
        )
}