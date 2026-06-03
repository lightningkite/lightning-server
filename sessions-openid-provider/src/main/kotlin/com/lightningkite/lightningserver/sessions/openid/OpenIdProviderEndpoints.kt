package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.Session
import com.lightningkite.lightningserver.sessions.SessionManager
import com.lightningkite.lightningserver.sessions.RefreshToken
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.lightningserver.sessions.token.TokenException
import com.lightningkite.lightningserver.sessions.token.signJwt
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.HttpAccess
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.serializer
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * OpenID Connect provider endpoints.
 *
 * Turns a Lightning Server application into an identity provider, so third-party applications can
 * offer "Sign in with [your app]". It implements the OpenID Connect Authorization Code flow with
 * PKCE on top of the application's existing [SessionManager]: access and refresh tokens issued to
 * relying parties are ordinary Lightning Server sessions tagged with the issuing client, which keeps
 * the session table the single source of truth for issuance and revocation.
 *
 * ## API-only design
 *
 * This provider deliberately does **not** render login or consent UI, nor does it own a browser
 * redirect handler. The host application's frontend (e.g. the Lightning Server + KiteUI auth
 * component) owns those. The advertised `authorization_endpoint` in discovery is a route in the
 * host frontend, not an endpoint here. That frontend:
 *
 * 1. Ensures the user is logged in (normal [SessionManager] session — its access token is sent as
 *    the Bearer token to this provider).
 * 2. Calls [authorizePrepare] with the parameters it received from the relying party. The response
 *    either contains a ready-to-use redirect (trusted client or pre-existing consent) or the data
 *    needed to render a consent screen.
 * 3. If consent is required, renders it and calls [authorizeApprove] with the granted scopes.
 * 4. Redirects the browser to the returned `redirectUri` (which carries `code` and `state`) — or,
 *    on denial, to `redirect_uri?error=access_denied`.
 *
 * The remaining endpoints ([token], [userinfo], [discovery], [jwks]) are machine-to-machine and
 * fully owned here.
 *
 * ## Signing keys
 *
 * [signingKey] signs ID tokens. **It must be persisted in production** (e.g. via a secret source);
 * a key regenerated on each restart invalidates every previously issued ID token. For development,
 * `Runtime.Cached { generateRS256Signer() }` is acceptable.
 *
 * @param sessions The application's existing session manager; tokens are issued as sessions on it.
 * @param database Database runtime (OAuth clients, user consents).
 * @param cache Cache runtime (short-lived single-use authorization codes).
 * @param signingKey RSA signer for ID tokens (see [generateRS256Signer]).
 * @param getUserClaims Maps an authenticated subject to OpenID Connect identity claims.
 * @param issuerUrl The issuer identifier (the `iss` claim; typically the server's public URL).
 * @param oauthBaseUrl Absolute base URL where these provider endpoints are mounted (e.g.
 *   `"${'$'}{generalSettings().publicUrl}/oauth"`); used to build discovery URLs.
 * @param authorizationUiUrl Absolute URL of the host frontend's authorization route, advertised as
 *   `authorization_endpoint`.
 * @param scopeDescriptions Human-readable descriptions per scope, for the consent screen.
 * @param keyId The JWKS key id (`kid`), placed in signed JWT headers.
 * @param authorizationCodeLifetime How long an authorization code remains valid (single-use).
 * @param accessTokenLifetime Reported `expires_in`; should match the [SessionManager.tokenFormat]'s
 *   access-token expiration.
 */
public class OpenIdProviderEndpoints<USER : HasId<ID>, ID : Comparable<ID>>(
    private val sessions: SessionManager<USER, ID>,
    private val database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val signingKey: RuntimeDeferred<Signer>,
    private val getUserClaims: suspend (USER) -> IdTokenClaims,
    private val issuerUrl: Runtime<String>,
    private val oauthBaseUrl: Runtime<String>,
    private val authorizationUiUrl: Runtime<String>,
    private val scopeDescriptions: Map<String, String> = defaultScopeDescriptions,
    private val keyId: String = "default",
    private val authorizationCodeLifetime: Duration = 10.minutes,
    private val accessTokenLifetime: Duration = 5.minutes,
) : ServerBuilder() {

    private val principal get() = sessions.principal

    private val authorizationErrorCases = listOf(
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing or invalid parameters"),
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Unknown client"),
        LSError(http = 400, detail = OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE, message = "Unsupported response type"),
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_SCOPE, message = "Invalid or disallowed scope"),
    )

    public companion object {
        /** Default human-readable descriptions for the standard OpenID Connect scopes. */
        public val defaultScopeDescriptions: Map<String, String> = mapOf(
            OpenIdScopes.OPENID to "Confirm your identity",
            OpenIdScopes.PROFILE to "View your basic profile (name, picture)",
            OpenIdScopes.EMAIL to "View your email address",
            OpenIdScopes.ADDRESS to "View your mailing address",
            OpenIdScopes.PHONE to "View your phone number",
            OpenIdScopes.OFFLINE_ACCESS to "Stay signed in when you're not using the app",
        )
    }

    // ----- Discovery & keys -------------------------------------------------------------------

    /** GET /.well-known/openid-configuration — provider metadata for relying parties. */
    public val discovery: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, ProviderMetadata> =
        path.path(".well-known").path("openid-configuration").get bind ApiHttpHandler(
            summary = "OpenID Connect Discovery",
            description = "Provider configuration metadata (endpoints, supported algorithms, scopes, claims).",
            auth = noAuth,
            successCode = HttpStatus.OK,
            implementation = { _: Unit ->
                val base = oauthBaseUrl()
                ProviderMetadata(
                    issuer = issuerUrl(),
                    authorization_endpoint = authorizationUiUrl(),
                    token_endpoint = "$base/token",
                    userinfo_endpoint = "$base/userinfo",
                    jwks_uri = "$base/jwks",
                    response_types_supported = listOf("code"),
                    subject_types_supported = listOf("public"),
                    id_token_signing_alg_values_supported = listOf(signingKey.await().name),
                    scopes_supported = listOf(
                        OpenIdScopes.OPENID, OpenIdScopes.PROFILE, OpenIdScopes.EMAIL,
                        OpenIdScopes.ADDRESS, OpenIdScopes.PHONE, OpenIdScopes.OFFLINE_ACCESS,
                    ),
                    token_endpoint_auth_methods_supported = listOf("client_secret_post", "none"),
                    claims_supported = listOf(
                        "sub", "iss", "aud", "exp", "iat", "auth_time", "nonce",
                        "name", "given_name", "family_name", "preferred_username", "picture",
                        "email", "email_verified", "phone_number", "phone_number_verified", "address",
                    ),
                    code_challenge_methods_supported = listOf("S256"),
                    grant_types_supported = listOf(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN),
                )
            }
        )

    /** GET /jwks — public keys for verifying ID token signatures. */
    public val jwks: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, JwksResponse> =
        path.path("jwks").get bind ApiHttpHandler(
            summary = "JSON Web Key Set",
            description = "Public keys used to verify the signatures of ID tokens issued by this provider.",
            auth = noAuth,
            successCode = HttpStatus.OK,
            implementation = { _: Unit -> JwksUtils.toJwks(signingKey.await(), keyId) }
        )

    // ----- Authorization (API-only; driven by the host frontend) ------------------------------

    /**
     * POST /authorize/prepare — validate an authorization request for the logged-in user.
     *
     * Authenticated as the end user (their normal session). Returns either a ready redirect (when
     * the client is trusted or consent already exists) or the data needed to render a consent screen.
     */
    public val authorizePrepare: ApiHttpHandler<PathSpec0, USER, AuthorizationRequest, AuthorizePrepareResponse> =
        path.path("authorize").path("prepare").post bind explicitApiHttpHandler(
            summary = "Prepare Authorization",
            description = "Validates an OAuth authorization request for the currently logged-in user and reports whether consent is needed.",
            auth = principal.require(),
            inputType = AuthorizationRequest.serializer(),
            outputType = AuthorizePrepareResponse.serializer(),
            errorCases = authorizationErrorCases,
            successCode = HttpStatus.OK,
            implementation = { request: AuthorizationRequest ->
                val (client, requestedScopes) = validateAuthorizationRequest(request)
                val userId = auth.id.toString()
                if (client.trusted || hasConsent(userId, client._id, requestedScopes)) {
                    AuthorizePrepareResponse(redirectUri = issueCode(request, userId, requestedScopes))
                } else {
                    AuthorizePrepareResponse(
                        consent = ConsentRequest(
                            clientId = client._id,
                            clientName = client.niceName,
                            clientLogo = client.logo,
                            requestedScopes = requestedScopes,
                            scopeDescriptions = requestedScopes.associateWith { scopeDescriptions[it] ?: it },
                        )
                    )
                }
            }
        )

    /**
     * POST /authorize/approve — record the user's consent and issue an authorization code.
     *
     * Authenticated as the end user. [AuthorizeApproveRequest.grantedScopes] must include `openid`
     * and be a subset of what the client requested. To deny, the frontend should not call this and
     * instead redirect to `redirect_uri?error=access_denied`.
     */
    public val authorizeApprove: ApiHttpHandler<PathSpec0, USER, AuthorizeApproveRequest, AuthorizeResult> =
        path.path("authorize").path("approve").post bind explicitApiHttpHandler(
            summary = "Approve Authorization",
            description = "Records the user's consent for the requested scopes and returns the redirect carrying the authorization code.",
            auth = principal.require(),
            inputType = AuthorizeApproveRequest.serializer(),
            outputType = AuthorizeResult.serializer(),
            errorCases = authorizationErrorCases,
            successCode = HttpStatus.OK,
            implementation = { approve: AuthorizeApproveRequest ->
                val request = approve.request
                val (client, requestedScopes) = validateAuthorizationRequest(request)

                val granted = approve.grantedScopes
                if (OpenIdScopes.OPENID !in granted) throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_SCOPE, message = "The 'openid' scope must be granted"
                )
                val notRequested = granted - requestedScopes
                if (notRequested.isNotEmpty()) throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_SCOPE,
                    message = "Granted scopes exceed requested: ${notRequested.joinToString(", ")}"
                )

                val userId = auth.id.toString()
                grantConsent(userId, client._id, granted)
                AuthorizeResult(redirectUri = issueCode(request, userId, granted))
            }
        )

    // ----- Token & UserInfo (machine-to-machine) ----------------------------------------------

    /** POST /token — exchange an authorization code, or a refresh token, for tokens. */
    public val token: ApiHttpHandler<PathSpec0, HasId<*>?, TokenRequest, TokenResponse> =
        path.path("token").post bind ApiHttpHandler(
            summary = "Token Exchange",
            description = "Exchanges an authorization code (or refresh token) for an ID token, access token, and optional refresh token.",
            auth = noAuth,
            errorCases = listOf(
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing or malformed parameter"),
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Code/refresh token invalid, expired, or already used"),
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client authentication failed"),
                LSError(http = 400, detail = OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, message = "Unsupported grant type"),
            ),
            successCode = HttpStatus.OK,
            implementation = { request: TokenRequest ->
                rateLimit("token", request.client_id) {
                    when (request.grant_type) {
                        GrantTypes.AUTHORIZATION_CODE -> handleAuthorizationCodeGrant(request)
                        GrantTypes.REFRESH_TOKEN -> handleRefreshTokenGrant(request)
                        else -> throw BadRequestException(
                            detail = OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE,
                            message = "Grant type '${request.grant_type}' is not supported"
                        )
                    }
                }
            }
        )

    /** GET /userinfo — standard claims for a valid access token, filtered by granted scopes. */
    public val userinfo: ApiHttpHandler<PathSpec0, USER, Unit, UserInfoResponse> =
        path.path("userinfo").get bind explicitApiHttpHandler(
            summary = "UserInfo",
            description = "Returns OpenID Connect claims for the bearer access token, filtered by the scopes it was granted.",
            auth = principal.require(scope = RequiredScope(OpenIdScopes.OPENID)),
            inputType = Unit.serializer(),
            outputType = UserInfoResponse.serializer(),
            errorCases = listOf(
                LSError(http = 401, detail = "invalid_token", message = "The access token is invalid, expired, or revoked")
            ),
            successCode = HttpStatus.OK,
            implementation = { _: Unit ->
                val user = auth.fetch()
                val claims = getUserClaims(user)
                val granted = auth.scopes.map { it.asString }.toSet()
                val hasProfile = OpenIdScopes.PROFILE in granted
                val hasEmail = OpenIdScopes.EMAIL in granted
                val hasAddress = OpenIdScopes.ADDRESS in granted
                val hasPhone = OpenIdScopes.PHONE in granted
                UserInfoResponse(
                    sub = claims.sub,
                    name = claims.name.takeIf { hasProfile },
                    given_name = claims.given_name.takeIf { hasProfile },
                    family_name = claims.family_name.takeIf { hasProfile },
                    middle_name = claims.middle_name.takeIf { hasProfile },
                    nickname = claims.nickname.takeIf { hasProfile },
                    preferred_username = claims.preferred_username.takeIf { hasProfile },
                    profile = claims.profile.takeIf { hasProfile },
                    picture = claims.picture.takeIf { hasProfile },
                    website = claims.website.takeIf { hasProfile },
                    gender = claims.gender.takeIf { hasProfile },
                    birthdate = claims.birthdate.takeIf { hasProfile },
                    zoneinfo = claims.zoneinfo.takeIf { hasProfile },
                    locale = claims.locale.takeIf { hasProfile },
                    updated_at = claims.updated_at.takeIf { hasProfile },
                    email = claims.email.takeIf { hasEmail },
                    email_verified = claims.email_verified.takeIf { hasEmail },
                    address = claims.address.takeIf { hasAddress },
                    phone_number = claims.phone_number.takeIf { hasPhone },
                    phone_number_verified = claims.phone_number_verified.takeIf { hasPhone },
                )
            }
        )

    /**
     * POST /end_session — OpenID Connect RP-initiated logout.
     *
     * Authenticated with the OAuth access token. Terminates that token's session **the same way
     * [SessionManager] session termination works** (via [SessionManager.terminate]), then, if a
     * `post_logout_redirect_uri` is supplied, validates it against the issuing client's registered
     * post-logout URIs and returns where the frontend should send the browser.
     */
    public val endSession: ApiHttpHandler<PathSpec0, USER, EndSessionRequest, EndSessionResponse> =
        path.path("end_session").post bind explicitApiHttpHandler(
            summary = "End Session (Logout)",
            description = "Terminates the access token's session and returns a validated post-logout redirect.",
            auth = principal.require(scope = RequiredScope(OpenIdScopes.OPENID)),
            inputType = EndSessionRequest.serializer(),
            outputType = EndSessionResponse.serializer(),
            errorCases = listOf(
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "post_logout_redirect_uri is not registered for this client"),
            ),
            successCode = HttpStatus.OK,
            implementation = { request: EndSessionRequest ->
                val sessionId = auth.sessionId?.let(Uuid::parse) ?: throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "No session associated with this token"
                )
                // Read the session (for its client) before terminating it the standard way.
                val session = sessions.sessionInfo.table().get(sessionId)
                sessions.terminate(sessionId)

                var redirect = request.post_logout_redirect_uri
                if (redirect != null) {
                    val client = session?.oauthClient?.let { getClient(it) }
                    if (client == null || redirect !in client.postLogoutRedirectUris) throw BadRequestException(
                        detail = OAuth2ErrorCodes.INVALID_REQUEST,
                        message = "post_logout_redirect_uri is not registered for this client"
                    )
                    if (request.state != null) redirect = appendParams(redirect, "state" to request.state)
                }
                EndSessionResponse(redirectUri = redirect)
            }
        )

    /**
     * POST /revoke — token revocation (RFC 7009).
     *
     * Client-authenticated. Revoking an access or refresh token terminates the underlying session
     * (via [SessionManager.terminate]), stopping further token issuance. Per the RFC this always
     * responds 200, even for an unknown token or one issued to a different client.
     */
    public val revoke: ApiHttpHandler<PathSpec0, HasId<*>?, RevocationRequest, Unit> =
        path.path("revoke").post bind ApiHttpHandler(
            summary = "Revoke Token",
            description = "Revokes an access or refresh token by terminating its session (RFC 7009).",
            auth = noAuth,
            errorCases = listOf(
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client authentication failed"),
            ),
            successCode = HttpStatus.OK,
            implementation = { request: RevocationRequest ->
                rateLimit("revoke", request.client_id) {
                    val client = getClient(request.client_id)
                    client.authenticate(request.client_secret)
                    val resolved = resolveToken(request.token, request.token_type_hint)
                    // Only revoke tokens actually issued to this client; otherwise silently succeed.
                    if (resolved != null && resolved.session.oauthClient == request.client_id) {
                        sessions.terminate(resolved.session._id)
                    }
                }
            }
        )

    /**
     * POST /introspect — token introspection (RFC 7662).
     *
     * Client-authenticated. Reports whether a token is active and its metadata. Returns
     * `active = false` for an invalid/expired/revoked token or one issued to a different client.
     */
    public val introspect: ApiHttpHandler<PathSpec0, HasId<*>?, IntrospectionRequest, IntrospectionResponse> =
        path.path("introspect").post bind ApiHttpHandler(
            summary = "Introspect Token",
            description = "Returns whether a token is active and its metadata (RFC 7662).",
            auth = noAuth,
            errorCases = listOf(
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client authentication failed"),
            ),
            successCode = HttpStatus.OK,
            implementation = { request: IntrospectionRequest ->
                rateLimit("introspect", request.client_id) {
                    val client = getClient(request.client_id)
                    client.authenticate(request.client_secret)
                    val resolved = resolveToken(request.token, request.token_type_hint)
                    if (resolved == null || !resolved.active || resolved.session.oauthClient != request.client_id) {
                        IntrospectionResponse(active = false)
                    } else {
                        IntrospectionResponse(
                            active = true,
                            scope = resolved.scopes.joinToString(" ") { it.asString },
                            client_id = resolved.session.oauthClient,
                            username = resolved.session.subjectId.toString(),
                            sub = resolved.session.subjectId.toString(),
                            token_type = "Bearer",
                            iat = resolved.issuedAt.epochSeconds,
                            exp = resolved.expiration?.epochSeconds,
                        )
                    }
                }
            }
        )

    // ----- Internals --------------------------------------------------------------------------

    /**
     * Throttles the unauthenticated machine-to-machine endpoints (token/introspect/revoke) per client
     * per source IP. Defends against client-secret brute-forcing and abusive probing. Keyed by source
     * IP so one caller's failures cannot lock a legitimate client out, and counts only failures (the
     * underlying [constrainAttemptRate] clears the counter on success), so normal traffic is unaffected.
     *
     * This is module-level defense in depth; in production these endpoints should additionally sit
     * behind the application's general rate-limit interceptor.
     */
    context(server: ServerRuntime)
    private suspend inline fun <R> HttpAccess<*, *>.rateLimit(endpoint: String, clientId: String, action: () -> R): R =
        cache().constrainAttemptRate(
            cacheKey = "oidc-$endpoint-$clientId-${request.sourceIp}",
            count = 10,
            expires = 5.minutes,
            action = action,
        )

    /** A token resolved to its backing session, for revocation and introspection. */
    private inner class ResolvedToken(
        val session: Session<USER, ID>,
        val scopes: Set<GrantedScope>,
        val issuedAt: Instant,
        val expiration: Instant?,
        val active: Boolean,
    )

    context(server: ServerRuntime)
    private fun Session<USER, ID>.isActive(): Boolean =
        terminated == null &&
            (expires?.let { it > now() } ?: true) &&
            (stale?.let { it > now() } ?: true)

    /**
     * Resolves a token string (access or refresh) to its backing session. Tries the form indicated
     * by [hint] first, then the other. Returns null if the token can't be interpreted at all.
     */
    context(server: ServerRuntime)
    private suspend fun resolveToken(token: String, hint: String?): ResolvedToken? {
        suspend fun asAccess(): ResolvedToken? {
            val auth = try {
                sessions.tokenFormat().read(principal, token)
            } catch (e: TokenException) {
                null
            } ?: return null
            val sessionId = auth.sessionId?.let(Uuid::parse) ?: return null
            val session = sessions.sessionInfo.table().get(sessionId) ?: return null
            return ResolvedToken(session, auth.scopes, auth.issuedAt, auth.expiration, session.isActive())
        }

        suspend fun asRefresh(): ResolvedToken? {
            val refresh = RefreshToken(token)
            if (!refresh.valid || refresh.type != principal.name) return null
            val session = sessions.sessionInfo.table().get(refresh._id) ?: return null
            if (!refresh.plainTextSecret.checkAgainstHash(session.secretHash)) return null
            return ResolvedToken(session, session.scopes, session.createdAt, session.expires, session.isActive())
        }

        return if (hint == "refresh_token") asRefresh() ?: asAccess() else asAccess() ?: asRefresh()
    }

    context(server: ServerRuntime)
    private suspend fun getClient(clientId: String): OauthClient =
        database().table<OauthClient>().get(clientId) ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client '$clientId' is not registered"
        )

    private fun OauthClient.hasActiveSecret(): Boolean = secrets.any { it.disabledAt == null }

    /** Validates a client's confidential-client authentication (secret) when it has active secrets. */
    context(server: ServerRuntime)
    private suspend fun OauthClient.authenticate(clientSecret: String?) {
        if (!hasActiveSecret()) return  // public client; authenticated via PKCE instead
        if (clientSecret == null) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client authentication required"
        )
        val ok = secrets.filter { it.disabledAt == null }.any { clientSecret.checkAgainstHash(it.secretHash) }
        if (!ok) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Invalid client credentials"
        )
    }

    /** Common validation for prepare/approve. Returns the client and the parsed requested scopes. */
    context(server: ServerRuntime)
    private suspend fun validateAuthorizationRequest(request: AuthorizationRequest): Pair<OauthClient, Set<String>> {
        if (request.response_type != "code") throw BadRequestException(
            detail = OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE,
            message = "Only 'code' response type is supported"
        )
        val client = getClient(request.client_id)

        if (request.redirect_uri !in client.redirectUris) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST,
            message = "redirect_uri is not registered for this client"
        )
        if (!isSecureRedirectUri(request.redirect_uri)) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST,
            message = "redirect_uri must use HTTPS (HTTP is allowed only for localhost)"
        )

        val requestedScopes = request.scope.split(' ').filter { it.isNotBlank() }.toSet()
        if (OpenIdScopes.OPENID !in requestedScopes) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_SCOPE, message = "The 'openid' scope is required"
        )
        val disallowed = requestedScopes - client.scopes
        if (disallowed.isNotEmpty()) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_SCOPE,
            message = "Client is not allowed scopes: ${disallowed.joinToString(", ")}"
        )

        // PKCE: required for public clients and any client with requirePkce; only S256 is accepted.
        if (request.code_challenge != null) {
            if ((request.code_challenge_method ?: "plain") != "S256") throw BadRequestException(
                detail = OAuth2ErrorCodes.INVALID_REQUEST,
                message = "Only the S256 code_challenge_method is supported"
            )
        } else if (!client.hasActiveSecret() || client.requirePkce) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST,
            message = "PKCE (code_challenge with S256) is required for this client"
        )

        return client to requestedScopes
    }

    /** Creates a single-use authorization code in cache and returns the client redirect URL. */
    context(server: ServerRuntime)
    private suspend fun issueCode(request: AuthorizationRequest, userId: String, grantedScopes: Set<String>): String {
        val code = java.util.UUID.randomUUID().toString()
        cache().set(
            "oidc_auth_code:$code",
            AuthorizationCode(
                clientId = request.client_id,
                userId = userId,
                redirectUri = request.redirect_uri,
                scope = grantedScopes.joinToString(" "),
                nonce = request.nonce,
                codeChallenge = request.code_challenge,
                codeChallengeMethod = request.code_challenge_method,
                authTime = now().epochSeconds,
                createdAt = now(),
            ),
            authorizationCodeLifetime,
        )
        return appendParams(request.redirect_uri, "code" to code, "state" to request.state)
    }

    context(server: ServerRuntime)
    private suspend fun hasConsent(userId: String, clientId: String, requestedScopes: Set<String>): Boolean {
        val nowInstant = now()
        return database().table<UserConsent>()
            .find(condition { (it.userId eq userId) and (it.clientId eq clientId) })
            .toList()
            .any { c ->
                (c.expiresAt == null || c.expiresAt!! > nowInstant) && requestedScopes.all { it in c.scopes }
            }
    }

    context(server: ServerRuntime)
    private suspend fun grantConsent(userId: String, clientId: String, scopes: Set<String>) {
        database().table<UserConsent>().insertOne(
            UserConsent(userId = userId, clientId = clientId, scopes = scopes, grantedAt = now())
        )
    }

    context(server: ServerRuntime)
    private suspend fun handleAuthorizationCodeGrant(request: TokenRequest): TokenResponse {
        val code = request.code ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing required parameter: code"
        )
        val authCode = cache().get<AuthorizationCode>("oidc_auth_code:$code") ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Authorization code is invalid or expired"
        )
        cache().remove("oidc_auth_code:$code")  // single-use

        if (request.client_id != authCode.clientId) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client id does not match the authorization code"
        )
        val client = getClient(request.client_id)
        client.authenticate(request.client_secret)

        if (request.redirect_uri != null && request.redirect_uri != authCode.redirectUri) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "redirect_uri does not match"
        )

        val challenge = authCode.codeChallenge
        if (challenge != null) {
            validatePkce(challenge, request.code_verifier)
        } else if (!client.hasActiveSecret()) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "PKCE code_verifier is required"
        )

        return issueTokens(client, authCode.userId, authCode.scope, authCode.nonce, authCode.authTime)
    }

    context(server: ServerRuntime)
    private suspend fun handleRefreshTokenGrant(request: TokenRequest): TokenResponse {
        val refresh = request.refresh_token ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing required parameter: refresh_token"
        )
        val token = RefreshToken(refresh)
        if (!token.valid || token.type != principal.name) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Refresh token is invalid"
        )
        val session = sessions.sessionInfo.table().get(token._id)?.takeIf {
            it.terminated == null && (it.expires == null || it.expires!! > now())
        } ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Refresh token is invalid or expired"
        )
        if (!token.plainTextSecret.checkAgainstHash(session.secretHash)) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Refresh token is invalid"
        )
        if (session.oauthClient != request.client_id) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Refresh token was not issued to this client"
        )
        val client = getClient(request.client_id)
        client.authenticate(request.client_secret)

        // Re-issue an access token and ID token bound to the same session; the refresh token is reused.
        val user = principal.fetch(session.subjectId)
        val accessToken = sessions.presignToken(session, session.scopes)
        val idToken = buildIdToken(user, request.client_id, scopeStringOf(session.scopes), nonce = null, authTime = null)
        return TokenResponse(
            access_token = accessToken,
            expires_in = accessTokenLifetime.inWholeSeconds.toInt(),
            refresh_token = refresh,
            id_token = idToken,
            scope = scopeStringOf(session.scopes),
        )
    }

    /** Creates the backing session and the corresponding access token, ID token, and refresh token. */
    context(server: ServerRuntime)
    private suspend fun issueTokens(
        client: OauthClient,
        userIdString: String,
        scopeString: String,
        nonce: String?,
        authTime: Long,
    ): TokenResponse {
        val userId = try {
            server.internalSerialization.json.decodeFromString(principal.idSerializer, "\"$userIdString\"")
        } catch (e: Exception) {
            throw BadRequestException(detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Invalid subject id in code")
        }
        val user = principal.fetch(userId)

        val scopeSet = scopeString.split(' ').filter { it.isNotBlank() }.toSet()
        val grantedScopes = scopeSet.map { GrantedScope(it) }.toSet()
        val offline = client.allowRefreshTokens && OpenIdScopes.OFFLINE_ACCESS in scopeSet

        val expires = if (offline) sessions.sessionExpiration(user) else now() + 1.hours
        val stale = sessions.sessionStaleAfter(user)?.let { now() + it }
        val (session, refreshToken) = sessions.newSession(
            subjectId = userId,
            label = "OIDC: ${client._id}",
            expires = expires,
            stale = stale,
            scopes = grantedScopes,
            oauthClient = client._id,
        )

        val accessToken = sessions.presignToken(session, grantedScopes)
        val idToken = buildIdToken(user, client._id, scopeString, nonce, authTime)

        return TokenResponse(
            access_token = accessToken,
            expires_in = accessTokenLifetime.inWholeSeconds.toInt(),
            refresh_token = if (offline) refreshToken.string else null,
            id_token = idToken,
            scope = scopeString,
        )
    }

    context(server: ServerRuntime)
    private suspend fun buildIdToken(user: USER, clientId: String, scope: String, nonce: String?, authTime: Long?): String {
        val nowSeconds = now().epochSeconds
        val claims = getUserClaims(user).copy(
            iss = issuerUrl(),
            aud = clientId,
            iat = nowSeconds,
            exp = nowSeconds + accessTokenLifetime.inWholeSeconds,
            nonce = nonce,
            auth_time = authTime,
        )
        return signingKey.await().signJwt(claims, IdTokenClaims.serializer(), keyId)
    }

    private fun scopeStringOf(scopes: Set<GrantedScope>): String = scopes.joinToString(" ") { it.asString }

    private fun validatePkce(codeChallenge: String, codeVerifier: String?) {
        val verifier = codeVerifier ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing required parameter: code_verifier"
        )
        val computed = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        if (computed != codeChallenge) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_GRANT, message = "PKCE validation failed"
        )
    }

    /** HTTPS required, except localhost loopback and non-HTTP custom schemes (native apps). */
    private fun isSecureRedirectUri(uri: String): Boolean = when {
        uri.startsWith("https://", ignoreCase = true) -> true
        uri.startsWith("http://localhost", ignoreCase = true) -> true
        uri.startsWith("http://127.0.0.1", ignoreCase = true) -> true
        uri.startsWith("http://[::1]", ignoreCase = true) -> true
        !uri.startsWith("http://", ignoreCase = true) -> true
        else -> false
    }

    private fun appendParams(base: String, vararg params: Pair<String, String?>): String {
        val sb = StringBuilder(base)
        var first = !base.contains('?')
        for ((k, v) in params) {
            if (v == null) continue
            sb.append(if (first) '?' else '&')
            first = false
            sb.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        return sb.toString()
    }
}
