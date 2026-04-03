package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.toException
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.min
import kotlin.uuid.Uuid

/**
 * Provides a complete proof-based authentication system for user sessions.
 *
 * This class implements a flexible authentication flow where users prove their identity through
 * one or more authentication methods (proofs). Each proof method has an associated strength value,
 * and users must accumulate sufficient proof strength to authenticate successfully.
 *
 * ## Authentication Flow
 *
 * 1. User submits one or more proofs (e.g., password, email verification code, SMS code, biometric)
 * 2. System validates each proof and identifies the user
 * 3. System calculates total authentication strength from all valid proofs
 * 4. If total strength meets the required threshold, a session is created
 * 5. User receives a refresh token to maintain their session
 *
 * ## Proof System
 *
 * - Each proof method has a "strength" value indicating its security level
 * - Different properties (email, phone, etc.) can have multiple proof methods
 * - Required strength can vary per user based on security requirements
 * - Proofs have signatures to prevent tampering and expiration times
 *
 * ## Key Concepts
 *
 * - **Proof**: A signed piece of evidence that a user has verified a property (email, phone, password, etc.)
 * - **Proof Method**: A specific way to verify a property (e.g., email magic link, SMS PIN, password check)
 * - **Proof Strength**: A numeric value representing how secure a proof method is
 * - **Required Strength**: The minimum total strength needed to authenticate
 *
 * @param SUBJECT The type of user/entity being authenticated (must have an ID)
 * @param ID The type of the subject's unique identifier
 * @param principal Defines the type of principal being authenticated and how to fetch/manage users
 * @param database Runtime access to the database for session storage
 * @param proofSigner Cryptographic signer for creating and validating proof signatures
 * @param tokenFormat Format for generating session tokens (defaults to PrivateTinyTokenFormat)
 */
public abstract class AuthEndpoints<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : SessionManager<SUBJECT, ID>(principal, database, tokenFormat) {
    init {
        sdkSettings.defaultInfo = SdkModule.Info(principal.name + "Auth")
        sdkSettings.clientInterface =
            AuthClientEndpoints::class.info(principal.subjectSerializer, principal.idSerializer)
    }

    /**
     * Determines the minimum proof strength required for the given subject to authenticate.
     *
     * This abstract method allows customizing authentication requirements per user. For example:
     * - Admin users might require higher strength (e.g., 200)
     * - Regular users might require lower strength (e.g., 100)
     * - Users with security flags might require multi-factor authentication
     *
     * The actual strength required will be capped at the maximum strength the user can possibly
     * achieve with their established proof methods.
     *
     * @param subject The user attempting to authenticate
     * @return The minimum total proof strength required for this user to log in
     */
    context(server: ServerRuntime)
    public abstract suspend fun requiredProofStrengthFor(subject: SUBJECT): Int

    /**
     * Calculates the maximum possible proof strength a user can achieve with the given proof methods.
     *
     * For each unique property (email, phone, etc.), only the strongest proof method counts toward
     * the total. This prevents users from "stacking" multiple weak proofs for the same property.
     *
     * For example, if a user has:
     * - Email password (strength 100)
     * - Email magic link (strength 80)
     * - Phone SMS code (strength 90)
     *
     * The max possible strength is 100 (email) + 90 (phone) = 190, not 270.
     *
     * @param methods The collection of proof methods available to the user
     * @return The maximum total strength achievable
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

    private val errors = listOf(
        errorNoSingleUser,
        errorInvalidProof,
        errorIrrelevantProof,
        errorExpiredProof,
        errorNonexistentMethod
    )

    /**
     * Creates a new authenticated session if the user has provided sufficient proof strength.
     *
     * This internal method is called after proofs have been validated. It respects session
     * expiration limits from both the proof check result and the login request, using the
     * more restrictive of the two.
     *
     * @param request The login request containing session preferences (label, scopes, expiration)
     * @param result The proof validation result including whether authentication succeeded
     * @return A pair of (Session, RefreshToken) if authentication succeeded, null otherwise
     */
    context(_: ServerRuntime)
    protected suspend fun newSession(
        request: LogInRequest,
        result: ProofsCheckResult<ID>,
    ): Pair<Session<SUBJECT, ID>, RefreshToken>? {
        val subject = principal.fetch(result.id)

        return if (result.readyToLogIn) newSession(
            subjectId = result.id,
            label = request.label,
            expires = run {
                val a = result.maxExpiration
                val b = request.expires
                if (a != null && b != null) minOf(a, b) else a ?: b
            },
            scopes = request.scopes,
            stale = sessionStaleAfter(subject)?.let { now() + it }
        )
        else null
    }

    /**
     * GET /auth-requirements
     *
     * Returns authentication requirements for the currently authenticated user.
     *
     * This endpoint is used when a user needs to re-authenticate (e.g., for a sensitive operation)
     * or when they want to see what authentication methods are available to them.
     *
     * The endpoint returns:
     * - A list of proof options (methods the user has established)
     * - The required strength threshold they must meet
     * - For each method, the associated property value (e.g., their email address, partially masked)
     *
     * **Authentication:** Requires an active session with the "auth:requirements" scope.
     *
     * **Response:** [AuthRequirements] containing available proof methods and required strength.
     *
     * @see AuthRequirements
     * @see ProofOption
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
     * POST /login
     *
     * Attempts to authenticate a user using a list of proofs.
     *
     * This is a simplified login endpoint that accepts only a list of proofs without additional
     * options. For more control over session creation (expiration, label, scopes), use [login2] instead.
     *
     * **Authentication Flow:**
     * 1. Client submits one or more signed proofs
     * 2. System validates signatures and expiration
     * 3. System identifies the user from the proofs
     * 4. System calculates total proof strength
     * 5. If strength is sufficient, creates a session and returns a refresh token
     * 6. If insufficient, returns what additional proof methods are needed
     *
     * **Request Body:** List of [Proof] objects
     *
     * **Response:** [IdAndAuthMethods] containing:
     * - The user's ID
     * - Available proof options if more authentication is needed
     * - Required strength threshold
     * - Refresh token if authentication succeeded (null if more proofs needed)
     *
     * **Errors:**
     * - `no-single-user` (404): Proofs don't uniquely identify a single user
     * - `invalid-proof` (400): Proof signature is invalid
     * - `irrelevant-proof` (400): Proof doesn't match the identified user
     * - `expired-proof` (400): Proof has expired
     * - `nonexistent-proof-method` (400): Proof method not recognized
     *
     * @see login2 For login with session customization options
     * @see proofsCheck For checking authentication status without creating a session
     */
    public val login: ApiHttpHandler<PathSpec0, HasId<*>?, List<Proof>, IdAndAuthMethods<ID>> =
        path.path("login").post bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
//            belongsToInterface = belongsToInterface,
            implementation = { proofs: List<Proof> ->
                login2(LogInRequest(proofs))
            }
        )

    /**
     * POST /login2
     *
     * Attempts to authenticate a user with full control over session parameters.
     *
     * This is the full-featured login endpoint that allows specifying session customization options
     * such as expiration time, device label, and requested scopes.
     *
     * **Authentication Flow:**
     * Same as [login], but with additional session customization:
     * 1. Client submits proofs plus session preferences
     * 2. System validates proofs and identifies user
     * 3. If authentication succeeds, creates a session with the specified options
     *
     * **Request Body:** [LogInRequest] containing:
     * - `proofs`: List of authentication proofs
     * - `label`: Optional device/session label (e.g., "iPhone 12", "Chrome on Windows")
     * - `expires`: Optional expiration time for the session
     * - `scopes`: Optional set of permission scopes requested for the session
     *
     * **Response:** [IdAndAuthMethods] containing:
     * - The user's ID
     * - Available proof options if more authentication is needed
     * - Required strength threshold
     * - Refresh token if authentication succeeded (null if more proofs needed)
     *
     * **Errors:** Same as [login]
     *
     * @see login For simple login without session customization
     * @see LogInRequest
     */
    public val login2: ApiHttpHandler<PathSpec0, HasId<*>?, LogInRequest, IdAndAuthMethods<ID>> =
        path.path("login2").post bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = LogInRequest.serializer(),
            outputType = IdAndAuthMethods.serializer(principal.idSerializer),
            summary = "Log In With Limitations",
            description = "Attempt to log in as a ${principal.name} using various proofs.",
            errorCases = errors,
//            belongsToInterface = belongsToInterface,
            implementation = { input: LogInRequest ->
                proofsCheck(input.proofs).let {
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
     * POST /proofs-check
     *
     * Validates proofs and returns authentication status WITHOUT creating a session.
     *
     * This endpoint is useful for:
     * - Checking if proofs are valid before committing to a login
     * - Multi-step authentication flows where you want to validate each step
     * - Determining what additional proof methods are needed
     * - UI flows that show progressive authentication status
     *
     * Unlike [login] and [login2], this endpoint never creates a session or returns a refresh token,
     * even if the proofs are sufficient. It only validates and reports status.
     *
     * **Request Body:** List of [Proof] objects to validate
     *
     * **Response:** [ProofsCheckResult] containing:
     * - `id`: The user ID identified by the proofs
     * - `readyToLogIn`: Whether the proofs are sufficient to authenticate
     * - `strengthRequired`: The required proof strength threshold
     * - `options`: Available proof methods the user hasn't used yet
     * - `maxExpiration`: Maximum session expiration time based on the proofs provided
     *
     * **Errors:** Same as [login]
     *
     * **Use Cases:**
     * ```
     * // Example: Two-factor authentication flow
     * 1. User enters password -> proofsCheck returns readyToLogIn=false, shows SMS option
     * 2. User enters SMS code -> proofsCheck returns readyToLogIn=true
     * 3. Client calls login2 with both proofs to create session
     * ```
     *
     * @see login For creating a session
     * @see ProofsCheckResult
     */
    public val proofsCheck: ApiHttpHandler<PathSpec0, HasId<*>?, List<Proof>, ProofsCheckResult<ID>> =
        path.path("proofs-check").post bind explicitApiHttpHandler(
            auth = noAuth,
            inputType = ListSerializer(Proof.serializer()),
            outputType = ProofsCheckResult.serializer(principal.idSerializer),
            summary = "Check Proofs",
            description = "Check if you can log in as a ${principal.name} using various proofs.",
            errorCases = errors,
//            belongsToInterface = belongsToInterface,
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
                        message = listOfNotNull(
                            if (subjects.isEmpty()) "No user was" else "Multiple users were",
                            "found with the",
                            if (properties.size > 1) "properties" else null,
                            proofs
                                .groupBy { it.property }
                                .toList()
                                .joinToString(", ") { pair ->
                                    "${pair.first} [${pair.second.map { it.value }.distinct().joinToString()}]"
                                }
                        ).joinToString(" "),
                    )
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
     * REST endpoints for managing sessions at /sessions.
     *
     * Provides full CRUD operations for user sessions:
     * - **GET /sessions**: List all active sessions for the authenticated user
     * - **GET /sessions/{id}**: Get details of a specific session
     * - **DELETE /sessions/{id}**: Revoke/delete a specific session (logout from that device)
     * - **PATCH /sessions/{id}**: Update session details (e.g., change label)
     *
     * This allows users to:
     * - See all devices/locations they're logged in from
     * - Revoke access from lost or stolen devices
     * - Manage session labels for identification
     *
     * **Authentication:** Requires an active session (inherited from SessionManager)
     *
     * @see SessionManager For session creation and token management
     * @see Session For session data model
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