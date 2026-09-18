package com.lightningkite.lightningserver.sessions.proofs

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
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
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
 * WebAuthn replaces a shared secret with public-key cryptography: the authenticator — a platform
 * biometric sensor, a roaming security key, or a synced passkey provider — holds the private key and
 * signs a server-issued challenge. There is no secret to phish, reuse across sites, or recover from a
 * server breach. This class issues those challenges, verifies the responses with webauthn4j, stores
 * the results as [WebAuthNCredential], and mints a [Proof] that the subject's `AuthEndpoints` can
 * exchange for a session.
 *
 * ## Registration flow
 *
 * 1. Client calls [registerStart] and receives a challenge plus creation options
 * 2. Client hands those to `navigator.credentials.create()` (or the platform equivalent)
 * 3. Client calls [registerFinish] with the attested credential
 * 4. Server verifies it and stores a [WebAuthNCredential]
 *
 * Both endpoints require [proofMethodAuth], whose `maxAge` is 10 minutes: a passkey can only be added
 * by someone who authenticated *recently*, not merely by someone holding a long-lived session.
 *
 * ## Authentication flow
 *
 * 1. Client calls [start], optionally identifying the subject, and receives a challenge
 * 2. Client hands it to `navigator.credentials.get()`
 * 3. Client calls [prove] with the assertion and receives a signed [Proof]
 * 4. Client submits that proof to `AuthEndpoints.login` to obtain a session
 *
 * Neither endpoint requires authentication, since this is how a subject logs in. [start] does
 * additionally accept an authenticated caller, for re-authentication and step-up.
 *
 * ## Strength
 *
 * [info] reports a strength of 10. An assertion whose authenticator data carries the UV (user
 * verified) flag — a biometric or PIN, not merely a presence tap — is upgraded to 20, so an
 * application can demand verified presence by requiring a threshold above 10.
 *
 * ## What binds an assertion to this application
 *
 * Three independent checks, all of which matter:
 *
 * - **rpId** — the authenticator signs `SHA-256(rpId)`, which a client cannot forge. This is what
 *   confines a credential to your domain rather than the whole web.
 * - **origin** — matched against [allowedOrigins]. rpId alone is *not* sufficient, because a browser
 *   will mint an assertion for an rpId from any origin whose effective domain is subordinate to it.
 * - **challenge** — server-generated from [SecureRandom], [challengeLength] bytes wide, single-use,
 *   and valid only for [expiration].
 *
 * Beyond that, a credential is scoped to one principal type: [prove] resolves the credential by id
 * *and* [WebAuthNCredential.subjectType], and labels the resulting proof with the type the credential
 * actually belongs to. A credential registered under one principal type can therefore never produce a
 * proof for another, even where two types share an id space.
 *
 * ## Account enumeration
 *
 * [start] deliberately treats an unrecognized identification the same as a recognized one: it issues
 * and caches a real challenge either way, draws options from [proveOptions] either way, and [prove]
 * reports one opaque error for an unknown challenge, an unknown credential, and a bad signature
 * alike. Lookups are rate limited per identifier.
 *
 * One residual difference is inherent to the identified (non-discoverable) flow and cannot be removed
 * without abandoning it: a subject who has passkeys receives a non-empty `allowCredentials`, which
 * reveals both that the account exists and how many credentials it has. Applications that cannot
 * accept that should require discoverable credentials and never send an identification to [start].
 *
 * ## Native mobile clients
 *
 * A native app does not report a web origin, so [allowedOrigins] needs its platform-specific form as
 * well: iOS reports `https://<rpId>` (authorized via Associated Domains), while Android reports
 * `android:apk-key-hash:<base64url SHA-256 of the app signing certificate>` (authorized via Digital
 * Asset Links). The Android value differs per signing key, so debug and release builds are separate
 * entries, and under Play App Signing it is the Google-managed key that counts. The platform checks
 * its own well-known file before it will produce an assertion at all, so serving `assetlinks.json` /
 * `apple-app-site-association` from the rpId domain is required in addition to listing the origin
 * here.
 *
 * @param database Runtime access to the database holding [WebAuthNCredential] records
 * @param cache Runtime access to the cache holding in-flight challenges and rate-limit counters
 * @param proofSigner Signs the [Proof] this method issues
 * @param proofExpiration How long an issued [Proof] remains valid
 * @param challengeLength Challenge size in bytes
 * @param expiration How long an issued challenge stays usable. Sent to the client as `timeout` AND
 *   enforced server-side as the challenge's cache lifetime, so the two cannot drift apart.
 * @param rpId The relying party id the credentials are scoped to — your registrable domain, or a
 *   subdomain if you do not need credentials shared across subdomains
 * @param allowedOrigins The exact origins permitted to create and use these credentials, e.g.
 *   `https://app.example.com`.
 *
 *   This MUST be an explicit allowlist. [rpId] alone is not sufficient: the browser will happily mint
 *   an assertion for an [rpId] from *any* origin whose effective domain is a subordinate of it, so
 *   with an [rpId] of `example.com` an XSS or subdomain takeover on `anything.example.com` can
 *   produce assertions the authenticator itself considers valid. The origin check is what narrows
 *   that back down to your application. See **Native mobile clients** above for the non-web forms.
 * @param registrationForUser Supplies the per-subject creation options (user entity, algorithms,
 *   authenticator selection, attestation preference) for [registerStart]
 * @param proveOptions Supplies the per-request assertion options for [start]. It receives the
 *   resolved subject id, or null when the subject is unknown or unidentified — and because an
 *   unknown subject must be indistinguishable from a known one, it should not vary its result based
 *   on whether that argument is null.
 * @param webAuthnManager The verifier used for both registration and authentication.
 *
 *   The default performs **no attestation verification whatsoever**:
 *   [WebAuthnManager.createNonStrictWebAuthnManager] installs `NoneAttestationStatementVerifier` plus
 *   the `Null*` statement verifiers and both `Null*TrustworthinessVerifier`s. That is the right
 *   default for ordinary passkey logins, where attestation buys nothing, but it means setting
 *   [WebAuthN.Registration.RegistrationOptions.attestation] to [WebAuthN.Attestation.Direct] or
 *   [WebAuthN.Attestation.Enterprise] requests attestation from the authenticator and then ignores it
 *   — an authenticator allowlist built that way is decorative. Supply a strict [WebAuthnManager] with
 *   a `CertPathTrustworthinessVerifier` and a metadata source if you need to actually enforce one.
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

    /**
     * Identifies this method to `AuthEndpoints` and to clients choosing a login option.
     *
     * Strength 10 covers an ordinary user-presence assertion; [prove] reports 20 instead when the
     * authenticator sets the UV flag.
     */
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
     * The [WebAuthNCredential] table and the permissions governing it.
     *
     * Registration is the only way a credential is created and there is no deletion, so both `create`
     * and `delete` are [Condition.Never] — a subject revokes a passkey by setting `disabledAt`.
     *
     * A subject may read their own credentials and an administrator may read any, with
     * `attestationObject` and `transports` masked out. Writes are whitelisted rather than
     * blacklisted: only `disabledAt` is self-service and only an administrator may set `expiresAt`.
     * Everything else — `lastSignCount` above all, since it is the clone-detection counter — is
     * server-maintained and must not be writable by the credential's owner.
     *
     * @see rest
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

    /**
     * REST endpoints under `/credentials` for listing, inspecting and revoking stored passkeys,
     * governed by [modelInfo]'s permissions.
     *
     * Intended for a "your devices" management screen: a subject lists their own credentials and
     * disables ones they no longer control.
     */
    public val rest: ModelRestEndpoints<HasId<*>, WebAuthNCredential, String> =
        path.path("credentials") include ModelRestEndpoints(modelInfo)


    private fun challengeCacheKey(key: String): String =
        "webAuthN_challenge_${key}"

    /**
     * Signals "no subject matched that identification" to [constrainAttemptRate], which counts a thrown
     * action as a failed attempt. Never escapes [start].
     */
    private class NoSuchSubject : Exception(null, null, false, false)

    /**
     * Server-side state for an in-flight [registerStart] challenge, held in the cache under a random
     * id until [registerFinish] consumes it.
     *
     * Public only because it is serialized into the cache; it is not part of the HTTP surface and the
     * challenge id, not this record, is what the client holds.
     */
    @Serializable
    public data class RegistrationCache(
        val challenge: String,
        val residentKeyPreference: WebAuthN.GeneralPreference,
        val allowedAlgorithms: List<WebAuthN.PublicKeyCredentialParameters>,
        val userVerification: Boolean,
    )

    /**
     * Server-side state for an in-flight [start] challenge, held in the cache until [prove] consumes
     * it. As with [RegistrationCache], this is cache state rather than API.
     *
     * [subjectType] scopes which credentials [prove] will accept, and [allowCredentials] records the
     * ids offered to the client so the assertion can be held to them — empty means the discoverable
     * (usernameless) flow, where any credential of that subject type is acceptable.
     */
    @Serializable
    public data class AuthenticationCache(
        val challenge: String,
        val userVerification: Boolean,
        val subjectType: String,
        val allowCredentials: List<String>,
    )

    /** A fresh base64url challenge of [challengeLength] random bytes from [SecureRandom]. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun generate(): String {
        val bytes = ByteArray(challengeLength)
        SecureRandom().nextBytes(bytes)
        return WebAuthN.base64Encoder.encode(bytes)
    }

    /**
     * Whether [subject] has at least one usable passkey, and therefore whether `AuthEndpoints` should
     * offer this method as a login option for them.
     *
     * Counts only credentials that are neither disabled nor expired, and only those registered under
     * this [principal]'s type.
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

    /**
     * The subject's usable credentials, in the shape the client passes to the browser as
     * `excludeCredentials` (registration) or `allowCredentials` (authentication).
     */
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
     * POST /register-start
     *
     * Issues a challenge for creating a new passkey on the calling subject's account.
     *
     * **Authentication:** [proofMethodAuth]. The 10-minute `maxAge` means the caller must have
     * authenticated recently — adding a second factor is exactly the operation an attacker holding a
     * stolen long-lived session would want.
     *
     * **Request Body:** [WebAuthN.GeneralPreference] — how strongly a discoverable (resident) key is
     * wanted. `Required` forces one, `Discouraged` records the credential as non-resident, and
     * `Preferred` records whatever the authenticator reports back through the `credProps` extension.
     *
     * **Response:** [WebAuthN.Registration.RegistrationResponse] — a `challengeId` to hand back to
     * [registerFinish], plus the options for `navigator.credentials.create()`. `excludeCredentials`
     * lists the subject's existing credentials so an authenticator that already holds one for this
     * account declines rather than silently creating a duplicate.
     *
     * The challenge is cached for [expiration] under a random id and consumed exactly once.
     *
     * @see registerFinish
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
                    timeToLive = expiration
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

    /**
     * POST /register-finish
     *
     * Verifies a newly created credential and stores it against the calling subject.
     *
     * **Authentication:** [proofMethodAuth], as for [registerStart].
     *
     * **Request Body:** [WebAuthN.Registration.RegisterRequest] — the `challengeId` from
     * [registerStart], a human-readable `displayName` for the credential ("YubiKey 5C", "Touch ID"),
     * and the attested credential from the client.
     *
     * **Response:** empty on success.
     *
     * **Errors:**
     * - `No Challenge available` (400): the challenge id is unknown, already used, expired, or the
     *   challenge inside `clientDataJSON` does not match the one issued
     * - `Cross-origin WebAuthN requests are not permitted` (400): `clientDataJSON` reports the call
     *   came from a cross-origin context
     * - `Failed to verify Authenticator` (400): webauthn4j rejected the attestation — bad signature,
     *   wrong origin, wrong rpId, or user verification required but absent
     * - `Credential ID does not match the attested credential` (400): the client sent an id other
     *   than the one the authenticator attested to
     *
     * The stored `lastSignCount` is seeded from the attestation so clone detection has a baseline
     * from the first use onward.
     *
     * @see registerStart
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


    /**
     * POST /start
     *
     * Issues a challenge for signing in with an existing passkey.
     *
     * **Authentication:** optional. Unauthenticated is the login case; authenticated is a
     * re-authentication (step-up) case, where the caller must identify themselves as the subject they
     * already are.
     *
     * **Request Body:** [Identification] — the principal type name, plus an optional
     * `property`/`value` pair identifying the subject. Supply both or neither.
     *
     * Omitting them selects the discoverable (usernameless) flow: no `allowCredentials` is returned
     * and the authenticator offers whichever passkey it holds for this rpId. Supplying them returns
     * that subject's credential ids, which non-discoverable authenticators need in order to find the
     * right key.
     *
     * **Response:** [WebAuthN.Authentication.StartResponse] — a `challengeId` to hand back to
     * [prove], plus the options for `navigator.credentials.get()`.
     *
     * **Errors:**
     * - `Invalid Subject Type` (400): no registered principal type has that name
     * - `You must provide or ignore property and value together.` (400)
     * - `Identification does not match the authenticated subject` (400): an authenticated caller
     *   identified someone other than themselves
     * - `Too many attempts; please wait N minutes.` (400): the per-identifier rate limit, with
     *   exponential backoff
     *
     * An identification that matches no subject is *not* an error: it yields an ordinary response
     * with a real, cached challenge, so a caller cannot use this endpoint to test whether an account
     * exists. See the class documentation for the one residual difference this cannot hide.
     *
     * @see prove
     */

    //TODO: Deprecate and create second another method that takes a valid Proof as the identification. That will
    // prevent mass enumeration of identifiers to pull credential ids and existing account confirmations.
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
                        val id = try {
                            cache().constrainAttemptRate(
                                cacheKey = "webauthn-start-${subjectType}-${property}-${normalizedValue}"
                            ) {
                                handler.fetchUserIdString(property, normalizedValue) ?: throw NoSuchSubject()
                            }
                        } catch (e: NoSuchSubject) {
                            null
                        }
                        if (authOrNull != null && id != authOrNull?.rawId)
                            throw BadRequestException("Identification does not match the authenticated subject")
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
     * POST /prove
     *
     * Verifies an assertion and issues the [Proof] that `AuthEndpoints.login` exchanges for a
     * session.
     *
     * **Authentication:** none — this is how a subject logs in.
     *
     * **Request Body:** [WebAuthN.Authentication.ProveRequest] — the `challengeId` from [start] and
     * the assertion from `navigator.credentials.get()`.
     *
     * **Response:** a [Proof] over `"<SubjectType>/_id"`, where the type and id both come from the
     * stored credential rather than from anything the caller supplied. Strength is [info]'s 10, or
     * 20 when the authenticator data carries the UV flag.
     *
     * **Errors:**
     * - `Cross-origin WebAuthN requests are not permitted` (400)
     * - `No Challenge available` (400): unknown, already-used or expired challenge id, or a challenge
     *   mismatch
     * - `Failed to verify Authenticator` (400): deliberately covers an unknown credential id, a
     *   credential belonging to a different subject type, and a failed signature check alike, so the
     *   three cannot be told apart
     *
     * On success the credential's `lastUsedAt` and `lastSignCount` are updated. A sign count that
     * moves backwards indicates a cloned authenticator and is rejected by webauthn4j before this
     * point, though only when the counts are non-zero — synced passkeys report zero and are exempt.
     *
     * @see start
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
                    ?: throw BadRequestException("No Challenge available")

                if (fromCache.challenge != WebAuthN.base64Decoder.decode(clientData.challenge).decodeToString())
                    throw BadRequestException("No Challenge available")

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
                    // Deliberately the same opaque error the signature check below raises: an unknown credential
                    // id, a credential belonging to another subject, and a bad signature must not be told apart.
                    ?: throw BadRequestException("Failed to verify Authenticator")

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
                    webAuthnManager.verify(
                        /* authenticationRequest = */ authRequest,
                        /* authenticationParameters = */ authParams
                    )
                } catch (e: VerificationException) {
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
                    }
                )

                proofSigner.await().makeProof(
                    info = if (authData.authenticatorData?.isFlagUV == true) info.copy(strength = 20) else info,
                    property = "${publicKeyCredential.subjectType}/_id",
                    value = publicKeyCredential.subjectId,
                )
            }
        )
}