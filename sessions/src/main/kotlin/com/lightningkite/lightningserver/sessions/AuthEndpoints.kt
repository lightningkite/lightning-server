package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.claimOnce
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.math.min
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Provides proof-based authentication endpoints for sessions of [SUBJECT].
 *
 * A user proves their identity with one or more proofs, each from a proof method with a strength. A session is created once the total strength of the proofs, counting only the strongest method for each property, reaches [requiredProofStrengthFor] the user, and the user receives a refresh token.
 *
 * @param principal Defines the type of principal being authenticated and how to fetch it.
 * @param tokenFormat The format of generated session tokens.
 */
public abstract class AuthEndpoints<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : SessionManager<SUBJECT, ID>(principal, database, tokenFormat) {
    init {
        sdkSettings.defaultInfo = SdkModule.Info(principal.name + "Auth")
        sdkSettings.clientInterface =
            AuthClientEndpoints::class.info(principal.subjectSerializer, principal.idSerializer)
    }

    /**
     * Returns the minimum total proof strength [subject] needs to log in.
     *
     * The strength actually required is capped at the maximum the subject can reach with their established proof methods.
     */
    context(server: ServerRuntime)
    public abstract suspend fun requiredProofStrengthFor(subject: SUBJECT): Int

    /**
     * Returns the maximum total strength reachable with [methods].
     *
     * Only the strongest method for each property counts, so email password (100), email magic link (80) and phone SMS (90) give 190, not 270.
     */
    private fun maxStrengthPossible(methods: Collection<ProofMethod>): Int =
        methods
            .groupBy { it.info.property ?: it.info.via }
            .values
            .sumOf { group -> group.maxOf { it.info.strength } }

    private val errorNoSingleUser = LSError(
        404,
        detail = "no-single-user",
        message = "No single user '' was found."
    )
    private val errorInvalidProof = LSError(
        400,
        detail = "invalid-proof",
        message = "A given proof was invalid."
    )
    private val errorIrrelevantProof = LSError(
        400,
        detail = "irrelevant-proof",
        message = "A given proof was not related to the user."
    )
    private val errorExpiredProof = LSError(
        400,
        detail = "expired-proof",
        message = "A given proof expired."
    )
    private val errorNonexistentMethod = LSError(
        400,
        detail = "nonexistent-proof-method",
        message = "Could not find proof method for given proof."
    )
    private val errorProofAlreadyUsed = LSError(
        400,
        detail = "proof-already-used",
        message = "A proof has already been used to create a session."
    )

    private val errors = listOf(
        errorNoSingleUser,
        errorInvalidProof,
        errorIrrelevantProof,
        errorExpiredProof,
        errorNonexistentMethod,
        errorProofAlreadyUsed
    )

    /**
     * Returns a new session and its refresh token if [result] shows enough proof strength, or `null` otherwise.
     *
     * Each of the proofs in [request] is marked as used, so it cannot create another session. The session expires at the earlier of `maxExpiration` in [result] and the expiration in [request], where each is present.
     *
     * A proof that has already created a session fails with `proof-already-used` (400).
     */
    context(_: ServerRuntime)
    protected suspend fun newSession(
        request: LogInRequest,
        result: ProofsCheckResult<ID>,
    ): Pair<Session<SUBJECT, ID>, RefreshToken>? {
        if (!result.readyToLogIn) return null

        // Consume each proof single-use at the only state-changing step: session creation. proofsCheck
        // mints nothing and stays freely re-callable (so the multi-step login UX is unaffected); burning
        // the proofs only here means a stolen/replayed proof cannot be exchanged for a second session.
        // Keyed on a deterministic hash of the signature (never the raw signature). claimOnce is atomic,
        // so two concurrent logins racing the same proof set yield exactly one session.
        request.proofs.forEach { proof ->
            val remaining = (proof.expiresAt?.minus(now()) ?: 1.hours).coerceAtLeast(1.seconds)
            if (!cache().claimOnce("proof-used-${proofSignatureFingerprint(proof.signature)}", remaining))
                throw errorProofAlreadyUsed.toException(data = proof.via)
        }

        val subject = principal.fetch(result.id)
        return newSession(
            subject = subject,
            label = request.label,
            expires = run {
                val a = result.maxExpiration
                val b = request.expires
                if (a != null && b != null) minOf(a, b) else a ?: b
            },
            scopes = request.scopes,
            stale = sessionStaleAfter(subject)?.let { now() + it }
        )
    }

    /** Returns a SHA-256 fingerprint of [signature], so the single-use cache key holds no replayable proof material. */
    private fun proofSignatureFingerprint(signature: String): String =
        Base64.UrlSafe.encode(MessageDigest.getInstance("SHA-256").digest(signature.encodeToByteArray()))

    /**
     * Returns the proof options and required strength the authenticated user must meet to re-authenticate.
     *
     * Requires a session with the `auth:requirements` scope.
     */
    public val authRequirements: ApiHttpHandler<PathSpec0, SUBJECT, Unit, AuthRequirements> =
        path.path("auth-requirements").get bind explicitApiHttpHandler(
            auth = principal.require(scopes = setOf(RequiredScope("auth:requirements"))),
            inputType = Unit.serializer(),
            outputType = AuthRequirements.serializer(),
            summary = "Authentication Requirements",
            description = "Returns a required strength and a list of proof options for the user to use in re-authenticating.",
            errorCases = listOf(),
            implementation = { _: Unit ->
                val subject = auth.fetch()

                val methods = serverRuntime.proofMethods.filter { it.established(principal, subject) }

                val maxPossible = maxStrengthPossible(methods)

                val requiredStrength = min(requiredProofStrengthFor(subject), maxPossible)

                AuthRequirements(
                    methods.map { method ->
                        ProofOption(
                            method = method.info,
                            value = method.info.property?.let { principal.getProperty(subject, it) }
                        )
                    },
                    requiredStrength
                )
            }
        )

    /**
     * Logs in with a list of [Proof]s, returning a refresh token if their strength is sufficient and the proof options still needed otherwise.
     *
     * Use [login2] to also set the session's expiration, label or scopes. The errors are `no-single-user` (404) and `invalid-proof`, `irrelevant-proof`, `expired-proof`, `nonexistent-proof-method` and `proof-already-used` (400).
     */
    public val login: ApiHttpHandler<PathSpec0, HasId<*>?, List<Proof>, IdAndAuthMethods<ID>> =
        path.path("login").post bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
            implementation = { proofs: List<Proof> ->
                login2(request, LogInRequest(proofs))
            }
        )

    /**
     * Logs in like [login], with the session's expiration, label and scopes taken from the [LogInRequest].
     *
     * Fails with the same errors as [login].
     */
    public val login2: ApiHttpHandler<PathSpec0, HasId<*>?, LogInRequest, IdAndAuthMethods<ID>> =
        path.path("login2").post bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = LogInRequest.serializer(),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In With Limitations",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
            implementation = { input: LogInRequest ->
                proofsCheck(request, input.proofs).let {
                    IdAndAuthMethods(
                        id = it.id,
                        options = it.options,
                        strengthRequired = it.strengthRequired,
                        refreshToken = newSession(input, it)?.second?.string
                    )
                }
            }
        )

    /**
     * Returns the status of a list of [Proof]s as a [ProofsCheckResult] without creating a session.
     *
     * Unlike [login] and [login2], this never returns a refresh token even when the proofs are sufficient, so it suits multi-step flows that validate each proof as it is entered. Fails with the same errors as [login].
     */
    public val proofsCheck: ApiHttpHandler<PathSpec0, HasId<*>?, List<Proof>, ProofsCheckResult<ID>> =
        path.path("proofs-check").post bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = ProofsCheckResult.serializer(principal.idSerializer),
            summary = "Check Proofs",
            description = "Check if you can log in as a ${principal.name} using various proofs.",
            errorCases = errors,
            implementation = { proofs: List<Proof> ->
                proofs.forEach { proof ->
                    if (serverRuntime.proofMethods.find { it.info.via == proof.via }?.isValid(proof) != true)
                        throw errorInvalidProof.toException(data = proof.via)
                }
                val used = proofs.map { it.via }.toSet()
                val subjects =
                    proofs.mapNotNull { principal.fetchByProperty(it.property, it.value) }.distinctBy { it._id }

                val subject = subjects.singleOrNull() ?: run {
                    val properties = proofs.map { it.property }.toSet()
                    throw errorNoSingleUser.toException(
                        message = buildString {
                            if (subjects.isEmpty()) append("No user was found ") else append("Multiple users were found ")
                            if (properties.size > 1) append("with the properties ") else append("with ")
                            proofs
                                .groupBy { it.property }
                                .toList()
                                .joinTo(this, ", ") { pair ->
                                    "${pair.first} [${pair.second.map { it.value }.distinct().joinToString()}]"
                                }
                        },
                    )
                }

                if (!permitAuthentication(subject)) {
                    throw ForbiddenException()
                }

                proofs.forEach {
                    if (principal.getProperty(subject, it.property) != it.value)
                        throw errorIrrelevantProof.toException(data = it.via)
                }

                val methods = serverRuntime.proofMethods
                    .filter { it.established(principal, subject) }
                    .associateBy { it.info.via }

                val strength = proofs
                    .groupBy { proof ->
                        val method = methods[proof.via] ?: throw errorNonexistentMethod.toException(data = proof.via)
                        method.info.property ?: method.info.via
                    }
                    .values
                    .sumOf { proofs -> proofs.maxOf { it.strength } }

                val maxPossible = maxStrengthPossible(methods.values)

                val requiredStrength = min(requiredProofStrengthFor(subject), maxPossible)

                ProofsCheckResult(
                    readyToLogIn = strength >= requiredStrength,
                    maxExpiration = sessionExpiration(subject),
                    id = subject._id,
                    options = methods.values
                        .filter { it.info.via !in used }
                        .map {
                            ProofOption(
                                method = it.info,
                                value = it.info.property?.let { p ->
                                    principal.getProperty(subject, p)
                                }
                            )
                        },
                    strengthRequired = requiredStrength
                )
            }
        )

    /**
     * Provides REST endpoints at `/sessions` for the authenticated user to list, read, update and delete their own sessions.
     *
     * Deleting a session logs that device out.
     */
    public val sessions: ModelRestEndpoints<SUBJECT, Session<SUBJECT, ID>, Uuid> =
        path.path("sessions") include ModelRestEndpoints(info = sessionInfo)
}

/*
 * TODO: API Recommendations
 *
 * 1. PROOF STRENGTH CONFIGURATION
 *    - Consider adding an endpoint to query global proof method information (strength values, descriptions)
 *    - This would help clients understand why they need multiple proofs
 *    - Example: GET /auth/proof-methods -> List<ProofMethodInfo>
 *
 * 2. SESSION MANAGEMENT IMPROVEMENTS
 *    - Add bulk session revocation: DELETE /sessions?all=true (logout from all devices except current)
 *    - Add session activity tracking: GET /sessions/{id}/activity to see last used time, IP, etc.
 *    - Consider adding session refresh endpoint separate from token refresh
 *
 * 3. PROGRESSIVE AUTHENTICATION FEEDBACK
 *    - The current design requires clients to submit all proofs at once
 *    - Consider supporting incremental proof submission where each call returns updated state
 *    - This would improve UX for step-by-step authentication flows
 *    - Example: POST /login/add-proof with state token to accumulate proofs across requests
 *
 * 4. PROOF METHOD DISCOVERY
 *    - Add endpoint for unauthenticated users to discover available proof methods for an identifier
 *    - Example: POST /auth/methods with {email: "user@example.com"} -> available methods
 *    - This helps clients show appropriate auth forms without prior knowledge
 *    - Security: Consider rate limiting and returning generic responses to prevent user enumeration
 *
 * 5. ERROR RESPONSES
 *    - Some errors expose detailed information that could aid attackers (e.g., "no user found")
 *    - Consider generic "authentication failed" responses for production security
 *    - Provide detailed errors only in development mode or to authenticated admins
 *
 * 6. PROOF EXPIRATION
 *    - The 1-hour default proof expiration may be too long for high-security contexts
 *    - Consider making expiration configurable per proof method (SMS code: 5 min, password: 1 hour)
 *    - Add endpoint to extend proof expiration for active authentication flows
 *
 * 7. NAMING CLARITY
 *    - "login2" is not intuitive - consider renaming to "login-advanced" or "login-with-options"
 *    - Consider deprecating "login" in favor of "login2" to reduce API surface area
 *    - Alternatively, merge both into one endpoint with optional parameters
 *
 * 8. WEBSOCKET SUPPORT
 *    - For real-time authentication flows (especially for device-to-device auth)
 *    - Consider WebSocket endpoint that streams authentication state changes
 *    - Useful for "approve login from another device" flows
 *
 * 9. PROOF REUSE PREVENTION
 *    - Current implementation validates proof signatures but doesn't prevent replay attacks within expiration
 *    - Consider adding nonce/jti to proofs and tracking used proof IDs
 *    - This prevents attackers from reusing intercepted proofs
 *
 * 10. DOCUMENTATION IMPROVEMENTS
 *     - Add OpenAPI examples showing complete authentication flows
 *     - Document recommended strength values for different security levels
 *     - Provide client SDK examples for common authentication patterns
 */