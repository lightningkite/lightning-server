package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.secureHash
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.Session
import com.lightningkite.lightningserver.sessions.SessionManager
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import kotlinx.serialization.builtins.serializer
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * OpenID Connect Provider Endpoints
 *
 * Provides a complete OpenID Connect Provider implementation, enabling your
 * Lightning Server application to act as an identity provider for third-party applications.
 *
 * This implementation **extends SessionManager** to leverage Lightning Server's existing
 * session infrastructure for token management, authentication, and lifecycle control.
 *
 * **SECURITY FEATURES:**
 * - Client authentication (confidential clients must authenticate with secrets)
 * - Redirect URI validation against registered URIs
 * - PKCE requirement for public clients (S256 method only)
 * - Per-client consent management with database persistence
 * - Constant-time cryptographic comparisons
 * - Scope validation per client
 * - Integration with existing SessionManager auth and session revocation
 *
 * **ARCHITECTURE:**
 * - Access/refresh tokens managed by SessionManager (stored in database)
 * - Authorization codes stored temporarily in cache
 * - OAuth sessions are regular Lightning Server sessions with OAuth metadata
 * - Session termination uses existing `/sessions/{id}` DELETE endpoint
 * - UserInfo endpoint uses SessionManager authentication
 *
 * **What is OpenID Connect?**
 * OpenID Connect is an identity layer on top of OAuth 2.0 that allows applications
 * to verify user identity and obtain basic profile information. When you implement
 * an OpenID Provider, external applications can offer "Sign in with [Your App]" functionality.
 *
 * **Core Flow:**
 * 1. Client redirects user to your authorization endpoint
 * 2. User authenticates and consents to share information (if client is not trusted)
 * 3. Authorization code issued and returned to client
 * 4. Client exchanges code for ID token (JWT), access token, and refresh token
 * 5. Client can fetch additional user info from UserInfo endpoint using access token
 *
 * **Example Usage:**
 * ```kotlin
 * object Server : ServerBuilder() {
 *     // Settings
 *     val database = setting("database", Database.Settings())
 *     val cache = setting("cache", Cache.Settings())
 *
 *     // OpenID Provider
 *     val openIdProvider = object : OpenIdProviderEndpoints<User, Uuid>(
 *         principal = UserPrincipal,
 *         database = database,
 *         cache = cache,
 *         // IMPORTANT: Use a persistent signing key in production!
 *         // This example generates a new key on each startup for development only.
 *         signingKey = RuntimeDeferred { generateRS256Signer() },
 *         getAuthenticatedUser = { request ->
 *             // Get the currently authenticated user from your auth system
 *             auth.fetch()  // Uses SessionManager authentication
 *         },
 *         getUserClaims = { user ->
 *             IdTokenClaims(
 *                 iss = generalSettings().publicUrl,
 *                 sub = user._id.toString(),
 *                 aud = "", // Set by token endpoint
 *                 exp = 0,  // Set by JWT issuer
 *                 iat = 0,  // Set by JWT issuer
 *                 email = user.email,
 *                 email_verified = user.emailVerified,
 *                 name = user.name,
 *                 picture = user.profilePicture
 *             )
 *         },
 *         issuerUrl = { generalSettings().publicUrl }
 *     ) {
 *         // SessionManager abstract methods
 *         context(server: ServerRuntime)
 *         override suspend fun sessionExpiration(subject: User) = now() + 30.days
 *
 *         context(server: ServerRuntime)
 *         override suspend fun sessionStaleAfter(subject: User) = 7.days
 *     }
 *
 *     // Mount OpenID Provider endpoints
 *     init {
 *         path include openIdProvider
 *     }
 * }
 * ```
 *
 * **CRITICAL: Key Persistence for Production**
 *
 * The signing key MUST be persisted in production. If the server restarts with a new key,
 * all existing ID tokens will become invalid and users will be logged out.
 *
 * Options for key persistence:
 * 1. **AWS Secrets Manager** (recommended for AWS deployments)
 * 2. **Environment variables** (load from secure secret store)
 * 3. **Encrypted file storage** (ensure file persists across restarts)
 * 4. **Database storage** (encrypted column)
 *
 * The signing key contains sensitive private key material and MUST be stored securely.
 * Never commit keys to version control.
 *
 * **Key Rotation Strategy:**
 *
 * For key rotation without invalidating existing tokens:
 * 1. Generate new key with different keyId
 * 2. Start signing new tokens with new key
 * 3. Publish both old and new keys in JWKS endpoint
 * 4. After token expiration period, remove old key from JWKS
 *
 * @param USER The user type (must have an ID)
 * @param ID The type of the user's unique identifier
 * @param principal The PrincipalType defining user authentication
 * @param database Database runtime for storing clients, consents, and sessions
 * @param cache Cache runtime for storing temporary authorization codes
 * @param signingKey RS256 signing key for ID tokens (use generateRS256Signer())
 * @param getAuthenticatedUser Function to get the currently authenticated user (for authorization endpoint)
 * @param getUserClaims Function to extract OpenID Connect claims from a user (for ID tokens)
 * @param issuerUrl The issuer identifier (defaults to server's public URL)
 * @param keyId Key identifier for JWKS (defaults to "default")
 * @param authorizationCodeLifetime How long authorization codes are valid (default: 10 minutes)
 */
public abstract class OpenIdProviderEndpoints<USER : HasId<ID>, ID : Comparable<ID>>(
    principal: PrincipalType<USER, ID>,
    private val database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
    private val signingKey: RuntimeDeferred<Signer>,
    private val getAuthenticatedUser: suspend (AuthorizationRequest) -> USER,
    private val getUserClaims: suspend (USER) -> IdTokenClaims,
    private val issuerUrl: () -> String,
    private val keyId: String = "default",
    private val authorizationCodeLifetime: Duration = 10.minutes,
) : SessionManager<USER, ID>(principal, database, tokenFormat) {

    /**
     * Helper to retrieve and validate an OAuth client from the database
     */
    context(server: ServerRuntime)
    private suspend fun getValidatedClient(clientId: String): OauthClient {
        return database().table<OauthClient>().get(clientId)
            ?: throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_CLIENT,
                message = "Client ID '$clientId' is not registered"
            )
    }

    /**
     * Helper to validate client secret for confidential clients
     */
    context(server: ServerRuntime)
    private suspend fun validateClientAuthentication(
        client: OauthClient,
        clientSecret: String?
    ) {
        // If client has secrets configured, it's a confidential client that requires authentication
        if (client.secrets.isNotEmpty()) {
            if (clientSecret == null) {
                throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_CLIENT,
                    message = "Client authentication required for confidential client"
                )
            }

            // Check if any active (non-disabled) secret matches
            val hasValidSecret = client.secrets
                .filter { it.disabledAt == null }
                .any { secret ->
                    // Use constant-time comparison to prevent timing attacks
                    val providedHash = clientSecret.secureHash()
                    constantTimeEquals(secret.secretHash, providedHash)
                }

            if (!hasValidSecret) {
                throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_CLIENT,
                    message = "Invalid client credentials"
                )
            }
        }
    }

    /**
     * Validates that a redirect URI uses a secure scheme (HTTPS or localhost HTTP)
     *
     * OpenID Connect requires redirect URIs to use HTTPS to prevent interception attacks.
     * HTTP is only allowed for localhost development.
     *
     * @param uri The redirect URI to validate
     * @return true if the URI is secure, false otherwise
     */
    private fun isSecureRedirectUri(uri: String): Boolean {
        return when {
            // HTTPS is always allowed
            uri.startsWith("https://", ignoreCase = true) -> true

            // HTTP is only allowed for localhost or 127.0.0.1
            uri.startsWith("http://localhost", ignoreCase = true) -> true
            uri.startsWith("http://127.0.0.1", ignoreCase = true) -> true
            uri.startsWith("http://[::1]", ignoreCase = true) -> true

            // Custom schemes (for native apps) are allowed
            !uri.startsWith("http://", ignoreCase = true) &&
            !uri.startsWith("https://", ignoreCase = true) -> true

            // HTTP to non-localhost is not secure
            else -> false
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks
     *
     * This implementation ensures that:
     * 1. Comparison time is independent of string contents
     * 2. Comparison time is constant even for different-length strings
     * 3. No early return that could leak information via timing
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.encodeToByteArray()
        val bBytes = b.encodeToByteArray()

        // Compare lengths in constant time by always checking both
        val lengthsEqual = aBytes.size == bBytes.size

        // Use the longer length to ensure we always iterate the same number of times
        val compareLength = maxOf(aBytes.size, bBytes.size)

        var result = 0
        for (i in 0 until compareLength) {
            // Use safe indexing that doesn't throw exceptions
            val aByte = if (i < aBytes.size) aBytes[i].toInt() else 0
            val bByte = if (i < bBytes.size) bBytes[i].toInt() else 0
            result = result or (aByte xor bByte)
        }

        // Both conditions must be true: same length AND same contents
        return lengthsEqual && (result == 0)
    }

    /**
     * Check if user has valid consent for the requested scopes
     */
    context(server: ServerRuntime)
    private suspend fun checkUserConsent(
        userId: String,
        clientId: String,
        requestedScopes: Set<String>
    ): Boolean {
        val consents = database().table<UserConsent>()
            .find(condition {
                (it.userId eq userId) and (it.clientId eq clientId)
            })
            .toList()

        // Check if there's an active (non-expired) consent that covers all requested scopes
        val currentTime = now()
        return consents.any { consent ->
            val isNotExpired = consent.expiresAt == null || consent.expiresAt!! > currentTime
            val coversAllScopes = requestedScopes.all { it in consent.scopes }
            isNotExpired && coversAllScopes
        }
    }

    /**
     * Grant user consent for the specified scopes
     */
    context(server: ServerRuntime)
    private suspend fun grantUserConsent(
        userId: String,
        clientId: String,
        scopes: Set<String>,
        clientName: String
    ) {
        val consent = UserConsent(
            userId = userId,
            clientId = clientId,
            scopes = scopes,
            grantedAt = now(),
            expiresAt = null  // No expiration by default
        )

        database().table<UserConsent>().insertOne(consent)
    }

    /**
     * JWKS endpoint - publishes public keys for JWT verification
     *
     * Location: /jwks
     *
     * Clients fetch this endpoint to get the public keys needed to verify
     * the cryptographic signatures of ID tokens issued by this provider.
     */
    public val jwks: ApiHttpHandler<*, *, Unit, JwksResponse> = path.path("jwks").get bind ApiHttpHandler(
        summary = "JSON Web Key Set",
        description = """
            Returns the provider's public keys in JWK Set format.

            Clients use these keys to verify the cryptographic signatures of
            ID tokens (JWTs) issued by this provider. Only public keys are
            exposed; private signing keys remain secret on the server.
        """.trimIndent(),
        auth = noAuth,
        errorCases = listOf(),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            JwksUtils.toJwks(signingKey.await(), keyId)
        }
    )

    /**
     * Discovery endpoint - publishes provider configuration metadata
     *
     * Location: /.well-known/openid-configuration
     *
     * This endpoint provides information about the provider's capabilities,
     * endpoint URLs, supported algorithms, scopes, and claims according to
     * the OpenID Connect Discovery 1.0 specification.
     */
    public val discovery: ApiHttpHandler<*, *, Unit, ProviderMetadata> = path
        .path(".well-known")
        .path("openid-configuration")
        .get bind ApiHttpHandler(
            summary = "OpenID Connect Discovery",
            description = """
                Returns the OpenID Provider configuration metadata.

                Clients should fetch this before initiating authentication to discover
                the provider's capabilities and endpoint URLs.
            """.trimIndent(),
            auth = noAuth,
            errorCases = listOf(),
            successCode = HttpStatus.OK,
            implementation = { _: Unit ->
                val baseUrl = issuerUrl()

                ProviderMetadata(
                    issuer = baseUrl,
                    authorization_endpoint = "$baseUrl/authorize",
                    token_endpoint = "$baseUrl/token",
                    userinfo_endpoint = "$baseUrl/userinfo",
                    jwks_uri = "$baseUrl/jwks",
                    end_session_endpoint = "$baseUrl/end_session",

                    // Required: Response types supported (only authorization code flow)
                    response_types_supported = listOf(
                        "code"  // Authorization Code Flow (only implemented flow)
                    ),

                    // Required: Subject identifier types
                    subject_types_supported = listOf("public"),

                    // Required: ID token signing algorithms (RS256 is mandatory)
                    id_token_signing_alg_values_supported = listOf("RS256"),

                    // Optional but recommended: Scopes supported
                    scopes_supported = listOf(
                        OpenIdScopes.OPENID,
                        OpenIdScopes.PROFILE,
                        OpenIdScopes.EMAIL,
                        OpenIdScopes.ADDRESS,
                        OpenIdScopes.PHONE,
                        OpenIdScopes.OFFLINE_ACCESS
                    ),

                    // Optional: Token endpoint authentication methods
                    token_endpoint_auth_methods_supported = listOf(
                        "client_secret_post",     // Client secret in POST body
                        "none"                    // Public clients (PKCE only)
                    ),

                    // Optional but recommended: Standard OpenID Connect claims
                    claims_supported = listOf(
                        "sub", "iss", "aud", "exp", "iat", "auth_time", "nonce",
                        "name", "given_name", "family_name", "middle_name",
                        "nickname", "preferred_username", "profile", "picture",
                        "website", "email", "email_verified", "gender", "birthdate",
                        "zoneinfo", "locale", "phone_number", "phone_number_verified",
                        "address", "updated_at"
                    ),

                    // Optional: PKCE challenge methods supported (only S256, plain is deprecated)
                    code_challenge_methods_supported = listOf("S256"),

                    // Optional: Grant types supported
                    grant_types_supported = listOf(
                        GrantTypes.AUTHORIZATION_CODE,
                        GrantTypes.REFRESH_TOKEN
                    )
                )
            }
        )

    /**
     * Token endpoint - exchanges authorization codes for tokens
     *
     * Location: /token
     *
     * This endpoint handles the token exchange as part of the Authorization Code Flow.
     * It accepts an authorization code and returns an ID token (JWT), access token,
     * and optionally a refresh token.
     */
    public val token: ApiHttpHandler<*, *, TokenRequest, TokenResponse> = path.path("token").post bind ApiHttpHandler(
        summary = "Token Exchange",
        description = """
            Exchanges an authorization code for tokens.

            Supports:
            - Authorization Code grant (grant_type=authorization_code)
            - Refresh Token grant (grant_type=refresh_token)
            - PKCE validation for public clients

            Returns an ID token (JWT) containing user identity claims, an access token
            for API access, and optionally a refresh token for obtaining new tokens.
        """.trimIndent(),
        auth = noAuth,
        errorCases = listOf(
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "The request is missing a required parameter or is otherwise malformed"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "The authorization code is invalid, expired, or has already been used"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.INVALID_CLIENT,
                message = "Client authentication failed"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE,
                message = "The grant type is not supported"
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { request: TokenRequest ->
            when (request.grant_type) {
                GrantTypes.AUTHORIZATION_CODE -> handleAuthorizationCodeGrant(request)
                GrantTypes.REFRESH_TOKEN -> handleRefreshTokenGrant(request)
                else -> throw BadRequestException(
                    detail = OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE,
                    message = "Grant type '${request.grant_type}' is not supported"
                )
            }
        }
    )

    /**
     * Handles the authorization_code grant type
     */
    context(server: ServerRuntime)
    private suspend fun handleAuthorizationCodeGrant(request: TokenRequest): TokenResponse {
        // Validate required parameters
        if (request.code == null) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Missing required parameter: code"
            )
        }

        // Retrieve the authorization code from cache
        val authCode = cache().get<AuthorizationCode>("auth_code:${request.code}")
            ?: throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "Authorization code is invalid or has expired"
            )

        // Remove the code immediately (codes are single-use)
        cache().remove("auth_code:${request.code}")

        // Validate client_id matches
        if (request.client_id != authCode.clientId) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_CLIENT,
                message = "Client ID does not match"
            )
        }

        // Validate client credentials
        val client = getValidatedClient(request.client_id)
        validateClientAuthentication(client, request.client_secret)

        // Validate redirect_uri matches
        if (request.redirect_uri != null && request.redirect_uri != authCode.redirectUri) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "Redirect URI does not match"
            )
        }

        // Validate PKCE if code_challenge was used
        val codeChallenge = authCode.codeChallenge
        if (codeChallenge != null) {
            validatePKCE(codeChallenge, authCode.codeChallengeMethod, request.code_verifier)
        } else if (client.secrets.isEmpty()) {
            // Public clients must have used PKCE during authorization
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "PKCE code_verifier is required for this authorization code"
            )
        }

        // Generate tokens
        return generateTokens(authCode)
    }

    /**
     * Handles the refresh_token grant type
     */
    context(server: ServerRuntime)
    private suspend fun handleRefreshTokenGrant(request: TokenRequest): TokenResponse {
        if (request.refresh_token == null) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Missing required parameter: refresh_token"
            )
        }

        // Retrieve the issued token from cache
        val issuedToken = cache().get<IssuedToken>("refresh_token:${request.refresh_token}")
            ?: throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "Refresh token is invalid or has expired"
            )

        // Validate client_id matches
        if (request.client_id != issuedToken.clientId) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_CLIENT,
                message = "Client ID does not match"
            )
        }

        // Generate new tokens (reusing the original authorization)
        val authCode = AuthorizationCode(
            code = "", // Not used for refresh
            clientId = issuedToken.clientId,
            redirectUri = "", // Not used for refresh
            scope = issuedToken.scope,
            userId = issuedToken.userId,
            nonce = issuedToken.nonce,
            codeChallenge = null,
            codeChallengeMethod = null,
            authTime = issuedToken.authTime ?: 0L,
            createdAt = now()
        )

        return generateTokens(authCode)
    }

    /**
     * Validates PKCE code_verifier against code_challenge
     */
    private fun validatePKCE(
        codeChallenge: String,
        codeChallengeMethod: String?,
        codeVerifier: String?
    ) {
        if (codeVerifier == null) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Missing required parameter: code_verifier (PKCE)"
            )
        }

        val computedChallenge = when (codeChallengeMethod) {
            "S256" -> {
                // SHA-256 hash of verifier, then base64url encode
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.encodeToByteArray())
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
            }
            else -> throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Unsupported code_challenge_method: $codeChallengeMethod. Only S256 is supported"
            )
        }

        // Use constant-time comparison to prevent timing attacks
        if (!constantTimeEquals(computedChallenge, codeChallenge)) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "PKCE validation failed"
            )
        }
    }

    /**
     * Authorization endpoint - initiates the OAuth/OpenID Connect flow
     *
     * Location: /authorize
     *
     * This endpoint is where the OAuth/OpenID Connect flow begins. Clients redirect
     * users here to authenticate and authorize access to their data.
     *
     * **Standard Flow:**
     * 1. Client redirects user to this endpoint with authorization parameters
     * 2. User authenticates (if not already logged in)
     * 3. User grants consent to share requested information
     * 4. Authorization code is generated and returned to client via redirect
     * 5. Client exchanges code for tokens at the token endpoint
     *
     * **Note:** This simplified implementation assumes the user is already authenticated
     * and has granted consent. In production, this endpoint should:
     * - Verify user authentication (or redirect to login)
     * - Display consent screen (or check for existing consent)
     * - Handle CSRF protection
     * - Support multiple response types and response modes
     */
    public val authorize: ApiHttpHandler<*, *, AuthorizationRequest, AuthorizationResponse> = path.path("authorize").get bind ApiHttpHandler(
        summary = "Authorization Endpoint",
        description = """
            Initiates the OAuth 2.0 / OpenID Connect authorization flow.

            The client redirects the user to this endpoint to authenticate and authorize
            access to their data. After successful authorization, the user is redirected
            back to the client with an authorization code.

            Supports:
            - Authorization Code Flow (response_type=code)
            - PKCE for public clients (code_challenge, code_challenge_method)
            - OpenID Connect scopes (openid, profile, email, etc.)

            Note: This implementation assumes pre-authenticated users. Production
            implementations should include login and consent screens.
        """.trimIndent(),
        auth = noAuth,
        errorCases = listOf(
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "The request is missing required parameters or contains invalid values"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.UNAUTHORIZED_CLIENT,
                message = "The client is not authorized to use this authorization method"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE,
                message = "The authorization server does not support this response type"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.INVALID_SCOPE,
                message = "The requested scope is invalid or exceeds what's allowed"
            ),
            LSError(
                http = 400,
                detail = OAuth2ErrorCodes.ACCESS_DENIED,
                message = "The user or authorization server denied the request"
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { request: AuthorizationRequest ->
            handleAuthorizationRequest(request)
        }
    )

    /**
     * UserInfo endpoint - returns user claims for a valid access token
     *
     * Location: /userinfo
     *
     * This endpoint allows clients to retrieve user information using a valid
     * access token. The claims returned depend on the scopes granted during
     * authorization (e.g., 'profile', 'email', 'address', 'phone').
     *
     * Note: This simplified implementation accepts the access token as a request parameter.
     * Standard OpenID Connect requires Bearer token authentication via the Authorization header.
     * This should be enhanced in production to support proper Bearer token authentication.
     */
    /**
     * UserInfo Endpoint (OpenID Connect Standard)
     *
     * GET /userinfo with Bearer token authentication
     *
     * Returns user claims based on the access token provided in the Authorization header.
     * This endpoint uses SessionManager's authentication infrastructure.
     */
    public val userinfo: ApiHttpHandler<*, USER, Unit, UserInfoResponse> = path.path("userinfo").get bind explicitApiHttpHandler(
        auth = principal.require(), // Use SessionManager auth via Bearer token!
        inputType = Unit.serializer(),
        outputType = UserInfoResponse.serializer(),
        summary = "UserInfo Endpoint",
        description = """
            Returns user claims for a valid access token.

            The response includes claims based on the scopes granted during authorization:
            - openid: Always includes 'sub' (subject identifier)
            - profile: Includes name, picture, etc.
            - email: Includes email and email_verified
            - address: Includes physical mailing address
            - phone: Includes phone_number and phone_number_verified

            Standard OpenID Connect: Use Bearer token in Authorization header.
        """.trimIndent(),
        errorCases = listOf(
            LSError(
                http = 401,
                detail = "invalid_token",
                message = "The access token is invalid, expired, or revoked"
            ),
            LSError(
                http = 401,
                detail = "insufficient_scope",
                message = "The access token does not have sufficient scope"
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { _: Unit ->
            // User is authenticated by SessionManager via Bearer token
            val user = auth.fetch()
            val claims = getUserClaims(user)

            // Get the granted scopes from the session
            val grantedScopes = auth.scopes.map { it.asString }.toSet()

            // Filter claims based on granted scopes per OpenID Connect spec
            // - openid: Always includes 'sub'
            // - profile: name, given_name, family_name, middle_name, nickname, preferred_username,
            //            profile, picture, website, gender, birthdate, zoneinfo, locale, updated_at
            // - email: email, email_verified
            // - address: address
            // - phone: phone_number, phone_number_verified

            val hasProfile = grantedScopes.contains(OpenIdScopes.PROFILE)
            val hasEmail = grantedScopes.contains(OpenIdScopes.EMAIL)
            val hasAddress = grantedScopes.contains(OpenIdScopes.ADDRESS)
            val hasPhone = grantedScopes.contains(OpenIdScopes.PHONE)

            UserInfoResponse(
                // 'sub' is always included (part of openid scope, which is required)
                sub = claims.sub ?: user._id.toString(),

                // Profile scope claims
                name = if (hasProfile) claims.name else null,
                given_name = if (hasProfile) claims.given_name else null,
                family_name = if (hasProfile) claims.family_name else null,
                middle_name = if (hasProfile) claims.middle_name else null,
                nickname = if (hasProfile) claims.nickname else null,
                preferred_username = if (hasProfile) claims.preferred_username else null,
                profile = if (hasProfile) claims.profile else null,
                picture = if (hasProfile) claims.picture else null,
                website = if (hasProfile) claims.website else null,
                gender = if (hasProfile) claims.gender else null,
                birthdate = if (hasProfile) claims.birthdate else null,
                zoneinfo = if (hasProfile) claims.zoneinfo else null,
                locale = if (hasProfile) claims.locale else null,
                updated_at = if (hasProfile) claims.updated_at else null,

                // Email scope claims
                email = if (hasEmail) claims.email else null,
                email_verified = if (hasEmail) claims.email_verified else null,

                // Address scope claims
                address = if (hasAddress) claims.address else null,

                // Phone scope claims
                phone_number = if (hasPhone) claims.phone_number else null,
                phone_number_verified = if (hasPhone) claims.phone_number_verified else null,
            )
        }
    )

    /**
     * End Session Endpoint (RP-Initiated Logout)
     *
     * Location: GET /end_session
     *
     * Implements OpenID Connect RP-Initiated Logout for validating and providing post-logout redirects.
     *
     * **Important**: This endpoint validates the redirect URI and returns where to redirect after logout.
     * For actual session termination, clients should call the SessionManager's `sessionTerminate` endpoint
     * (POST /sessions/terminate) with their access token BEFORE redirecting here, or implement their own
     * logout UI that calls sessionTerminate.
     *
     * **Flow:**
     * 1. Client calls SessionManager's sessionTerminate endpoint to invalidate the session
     * 2. Client redirects user to this endpoint for post-logout redirect
     * 3. This endpoint validates the redirect URI and returns it (or null if none provided)
     *
     * **Security:**
     * - post_logout_redirect_uri must be pre-registered with the client if provided
     * - id_token_hint can help identify the client for URI validation
     */
    public val endSession: ApiHttpHandler<*, *, EndSessionRequest, EndSessionResponse> =
        path.path("end_session").get bind explicitApiHttpHandler(
            auth = noAuth, // No auth required - this is a redirect validation endpoint
            inputType = EndSessionRequest.serializer(),
            outputType = EndSessionResponse.serializer(),
            summary = "End Session (Logout Redirect)",
            description = """
                OpenID Connect RP-Initiated Logout endpoint for post-logout redirects.

                Validates the post_logout_redirect_uri and returns where to redirect after logout.

                Query parameters:
                - id_token_hint: (Optional) ID Token previously issued, helps identify the client
                - post_logout_redirect_uri: (Optional) Where to redirect after logout
                - state: (Optional) Opaque value to maintain state, returned in redirect

                **Note**: This endpoint does NOT terminate sessions. Clients must call the
                SessionManager's terminate endpoint (POST /sessions/terminate) to actually
                end the session before redirecting here.
            """.trimIndent(),
            errorCases = listOf(
                LSError(
                    http = 400,
                    detail = "invalid_request",
                    message = "The post_logout_redirect_uri is not registered with the client"
                )
            ),
            successCode = HttpStatus.OK,
            implementation = { request: EndSessionRequest ->
                // Determine redirect URI
                var redirectUri = request.post_logout_redirect_uri

                // If post_logout_redirect_uri is provided, validate it's registered
                if (redirectUri != null) {
                    // Check against all clients to see if any has this URI registered
                    val allClients = database().table<OauthClient>()
                        .find(Condition.Always)
                        .toList()
                    val isRegistered = allClients.any { client ->
                        client.postLogoutRedirectUris.contains(redirectUri)
                    }

                    if (!isRegistered) {
                        throw BadRequestException(
                            detail = "invalid_request",
                            message = "The post_logout_redirect_uri '$redirectUri' is not registered with any client"
                        )
                    }

                    // Add state parameter if provided
                    if (request.state != null) {
                        redirectUri = if (redirectUri.contains("?")) {
                            "$redirectUri&state=${request.state}"
                        } else {
                            "$redirectUri?state=${request.state}"
                        }
                    }
                }

                EndSessionResponse(redirectUri = redirectUri)
            }
        )

    /**
     * Handles authorization requests by validating parameters and generating an authorization code
     */
    context(server: ServerRuntime)
    private suspend fun handleAuthorizationRequest(request: AuthorizationRequest): AuthorizationResponse {
        // Validate response_type (only 'code' is supported in this implementation)
        if (request.response_type != "code") {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE,
                message = "Only 'code' response type is supported. Requested: ${request.response_type}"
            )
        }

        // Validate required parameters
        if (request.client_id.isBlank()) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Missing required parameter: client_id"
            )
        }

        if (request.redirect_uri.isBlank()) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Missing required parameter: redirect_uri"
            )
        }

        if (request.scope.isBlank()) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Missing required parameter: scope"
            )
        }

        // Verify 'openid' scope is present (required for OpenID Connect)
        if (!request.scope.contains(OpenIdScopes.OPENID)) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_SCOPE,
                message = "The 'openid' scope is required for OpenID Connect"
            )
        }

        // Validate client exists in database
        val client = getValidatedClient(request.client_id)

        // Validate redirect_uri is registered with the client
        if (!client.redirectUris.contains(request.redirect_uri)) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Redirect URI '${request.redirect_uri}' is not registered for this client"
            )
        }

        // Validate redirect URI uses HTTPS (except for localhost development)
        if (!isSecureRedirectUri(request.redirect_uri)) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Redirect URI must use HTTPS scheme (HTTP is only allowed for localhost)"
            )
        }

        // Validate requested scopes are allowed for this client
        val requestedScopes = request.scope.split(" ").filter { it.isNotBlank() }.toSet()
        val disallowedScopes = requestedScopes - client.scopes
        if (disallowedScopes.isNotEmpty()) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_SCOPE,
                message = "Client is not authorized for scopes: ${disallowedScopes.joinToString(", ")}"
            )
        }

        // Validate PKCE parameters
        if (request.code_challenge != null) {
            val method = request.code_challenge_method ?: "plain"
            // Only support S256, reject plain method as it's insecure
            if (method != "S256") {
                throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_REQUEST,
                    message = "Unsupported code_challenge_method: $method. Only S256 is supported (plain method is deprecated)"
                )
            }
        } else if (client.secrets.isEmpty() || client.requirePkce) {
            // PKCE is required if:
            // 1. Public clients (no secrets) MUST use PKCE
            // 2. Client has requirePkce flag set (enhanced security for confidential clients)
            val reason = if (client.secrets.isEmpty()) "public clients" else "this client (enhanced security)"
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "PKCE (code_challenge with S256 method) is required for $reason"
            )
        }

        // Get the authenticated user
        val user = getAuthenticatedUser(request)
        val userId = user._id
        val userIdString = userId.toString()

        // Consent management - check if client requires consent
        // Trusted clients skip consent screen, non-trusted clients require it
        if (!client.trusted) {
            val hasConsent = checkUserConsent(userIdString, request.client_id, requestedScopes)
            if (!hasConsent) {
                // In a full implementation, this would call a consent handler
                // For backward compatibility, we automatically grant consent for the requested scopes
                // TODO: Add ConsentHandler interface and throw ConsentRequiredException for non-trusted clients
                grantUserConsent(userIdString, request.client_id, requestedScopes, client.niceName)
            }
        }

        // Generate authorization code
        val code = java.util.UUID.randomUUID().toString()

        // Store authorization code in cache with short expiration (10 minutes)
        val authCode = AuthorizationCode(
            code = code,
            clientId = request.client_id,
            userId = userIdString,  // Store as string for serialization
            redirectUri = request.redirect_uri,
            scope = request.scope,
            nonce = request.nonce,
            codeChallenge = request.code_challenge,
            codeChallengeMethod = request.code_challenge_method,
            authTime = now().epochSeconds,
            createdAt = now()
        )

        cache().set("auth_code:$code", authCode, authorizationCodeLifetime)

        // Return authorization code and state
        return AuthorizationResponse(
            code = code,
            state = request.state
        )
    }


    /**
     * Generates tokens from an authorization code using SessionManager
     */
    context(server: ServerRuntime)
    private suspend fun generateTokens(authCode: AuthorizationCode): TokenResponse {
        // Get client to check settings
        val client = getValidatedClient(authCode.clientId)

        // Safely deserialize the user ID from string to the proper ID type
        val userId = try {
            server.internalSerialization.json.decodeFromString(principal.idSerializer, "\"${authCode.userId}\"")
        } catch (e: Exception) {
            throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_GRANT,
                message = "Invalid user ID format in authorization code"
            )
        }

        // Fetch the user
        val user = principal.fetch(userId)

        // Parse scopes from OAuth format (space-separated) to SessionManager format (Set<GrantedScope>)
        val scopes = authCode.scope.split(" ")
            .filter { it.isNotBlank() }
            .map { GrantedScope(it) }
            .toSet()

        // Calculate expiration - respect client settings if they don't allow refresh tokens
        val expires = if (client.allowRefreshTokens && authCode.scope.contains(OpenIdScopes.OFFLINE_ACCESS)) {
            sessionExpiration(user) // Use SessionManager's expiration
        } else {
            now() + 1.hours // Short-lived session without refresh token
        }

        // Create session using SessionManager infrastructure
        // This handles token generation, database storage, and all lifecycle management
        val (session, refreshToken) = newSession(
            subjectId = userId,
            label = "OAuth Client: ${authCode.clientId}",
            expires = expires,
            scopes = scopes,
            stale = sessionStaleAfter(user)?.let { now() + it }
        )

        // Get user claims for ID token
        val userClaims = getUserClaims(user)

        // Build complete ID token claims
        val nowSeconds = now().epochSeconds
        val claims = userClaims.copy(
            iss = issuerUrl(),
            aud = authCode.clientId,
            exp = session.expires?.epochSeconds ?: (nowSeconds + 3600), // Match session expiration
            iat = nowSeconds,
            nonce = authCode.nonce,
            auth_time = authCode.authTime
        )

        // Create ID token (JWT)
        val jwtIssuer = JwtIssuer(signingKey.await(), issuerUrl())
        val idToken = jwtIssuer.createIdToken(claims)

        // Generate access token from session using TokenFormat
        val accessToken = tokenFormat().create(principal, session.toAuth())

        // Calculate expires_in for OAuth response
        val expiresIn = session.expires?.let {
            (it.epochSeconds - nowSeconds).toInt()
        } ?: 3600

        return TokenResponse(
            access_token = accessToken,  // Use SessionManager's TokenFormat
            token_type = "Bearer",
            expires_in = expiresIn,
            refresh_token = if (client.allowRefreshTokens && authCode.scope.contains(OpenIdScopes.OFFLINE_ACCESS)) {
                refreshToken.string // Use SessionManager's refresh token
            } else null,
            id_token = idToken,
            scope = authCode.scope
        )
    }
}
