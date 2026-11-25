package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.pathing.trailingSegments
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthClient
import com.lightningkite.lightningserver.sessions.proofs.oauth._id
import com.lightningkite.lightningserver.sessions.token.TokenException
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.getAndRemove
import com.lightningkite.services.cache.set
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.IdentityLens
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.sessions.openid.OpenIdProviderEndpoints")

/**
 * An OAuth 2.1 authorization server and OpenID Connect provider, built on the application's [SessionManager], so
 * remote MCP clients (Claude and others) and "Sign in with" relying parties can get tokens for a user.
 *
 * **Mount it at the server root** (`path include OpenIdProviderEndpoints(...)`).  The issuer is the server's public URL,
 * and the metadata documents must sit at `/.well-known/...` for clients to find them.  The endpoints are:
 * - `/.well-known/oauth-authorization-server` and `/.well-known/openid-configuration`: the server's metadata.
 * - `/.well-known/oauth-protected-resource/{path}`: metadata for each of [resources] (RFC 9728).
 * - `/oauth/authorize/prepare`, `approve`, `deny`: called by the frontend's consent screen with the user's own token.
 * - `/oauth/consents`: the user's consents, each of which they can withdraw.
 * - `/oauth/token`, `revoke`, `introspect`: called by clients, with OAuth-shaped error bodies.
 * - `/oauth/userinfo`, `jwks`, `end_session`: OpenID Connect.
 *
 * Tokens belong to an ordinary session tagged with the client ([Session.oauthClient]), with only the granted scopes and
 * the requested resource as its audience ([Session.audience]), so the session list shows and revokes them like any
 * other.  Clients are [OauthClient] rows registered by an admin; there is no dynamic registration.
 *
 * The provider renders no UI.  [authorizationUiUrl] is the frontend route that clients send the browser to; it logs
 * the user in, calls [authorizePrepare], shows the consent screen if needed, then calls [authorizeApprove] or
 * [authorizeDeny] and sends the browser to the redirect it gets back.
 *
 * @param signingKey Signs ID tokens; keep it in settings (see [OidcSigningKey]).
 * @param getUserClaims The identity claims for a user's ID token and userinfo.  The provider overwrites `iss`, `aud`,
 *   `exp`, `iat`, `nonce` and `auth_time`.
 * @param authorizationUiUrl The frontend route that handles authorization requests, advertised as the
 *   `authorization_endpoint`.
 * @param resources The resources tokens can be requested for, such as an MCP server.  They are registered in
 *   [protectedResources], so requests to them refuse other tokens and challenge with their metadata.
 * @param scopeDescriptions What each scope allows, for the consent screen.
 */
@ExperimentalOauthProviderApi
public class OpenIdProviderEndpoints<USER : HasId<ID>, ID : Comparable<ID>>(
    private val sessions: SessionManager<USER, ID>,
    database: Runtime<Database>,
    private val cache: Runtime<Cache>,
    private val signingKey: Runtime<OidcSigningKey>,
    private val getUserClaims: suspend (USER) -> IdTokenClaims,
    private val authorizationUiUrl: Runtime<String>,
    private val resources: List<ProtectedResource> = listOf(),
    private val scopeDescriptions: Map<String, String> = defaultScopeDescriptions,
) : ServerBuilder() {

    init {
        val scopes = resources.flatMap { it.scopes }
        require(scopes.size == scopes.toSet().size) {
            "Protected resources must not share scopes, or a token for one would work at another"
        }
        resources.forEach { protectedResources.register(it) }
    }

    public companion object {
        public val defaultScopeDescriptions: Map<String, String> = mapOf(
            OpenIdScopes.OPENID to "Confirm your identity",
            OpenIdScopes.PROFILE to "View your basic profile (name, picture)",
            OpenIdScopes.EMAIL to "View your email address",
            OpenIdScopes.ADDRESS to "View your mailing address",
            OpenIdScopes.PHONE to "View your phone number",
        )
        private val codeLifetime = 5.minutes
    }

    private val clients = database.registerTable<OauthClient>("OauthClient")
    private val consents = database.registerTable<UserConsent>("UserConsent")
    private val principal get() = sessions.principal
    private val sessionLens = IdentityLens(Session.serializer(sessions.principal.subjectSerializer, sessions.principal.idSerializer))
    private val oauth = path.path("oauth")

    // ----- Discovery ---------------------------------------------------------------------------------------------

    context(server: ServerRuntime)
    private suspend fun metadata(): AuthorizationServerMetadata {
        val clientAuthMethods = listOf("none", "client_secret_post", "client_secret_basic")
        return AuthorizationServerMetadata(
            issuer = generalSettings().publicUrl,
            authorization_endpoint = authorizationUiUrl(),
            token_endpoint = oauth.path("token").resolved().fullUrl(),
            revocation_endpoint = oauth.path("revoke").resolved().fullUrl(),
            introspection_endpoint = oauth.path("introspect").resolved().fullUrl(),
            userinfo_endpoint = oauth.path("userinfo").resolved().fullUrl(),
            end_session_endpoint = oauth.path("end_session").resolved().fullUrl(),
            jwks_uri = oauth.path("jwks").resolved().fullUrl(),
            scopes_supported = (resources.flatMap { it.scopes } + scopeDescriptions.keys).distinct(),
            response_types_supported = listOf("code"),
            grant_types_supported = listOf(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN),
            code_challenge_methods_supported = listOf("S256"),
            token_endpoint_auth_methods_supported = clientAuthMethods,
            revocation_endpoint_auth_methods_supported = clientAuthMethods,
            introspection_endpoint_auth_methods_supported = clientAuthMethods,
            authorization_response_iss_parameter_supported = true,
            subject_types_supported = listOf("public"),
            id_token_signing_alg_values_supported = listOf("RS256"),
            claims_supported = listOf(
                "sub", "iss", "aud", "exp", "iat", "auth_time", "nonce",
                "name", "given_name", "family_name", "preferred_username", "picture",
                "email", "email_verified", "phone_number", "phone_number_verified", "address",
            ),
        )
    }

    public val authorizationServerMetadata: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, AuthorizationServerMetadata> =
        path.path(".well-known").path("oauth-authorization-server").get bind ApiHttpHandler(
            summary = "OAuth Authorization Server Metadata",
            description = "RFC 8414 metadata: endpoints and supported methods.",
            auth = noAuth,
            implementation = { _: Unit -> metadata() }
        )

    public val openIdConfiguration: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, AuthorizationServerMetadata> =
        path.path(".well-known").path("openid-configuration").get bind ApiHttpHandler(
            summary = "OpenID Connect Discovery",
            description = "The same metadata as the OAuth authorization server metadata.",
            auth = noAuth,
            implementation = { _: Unit -> metadata() }
        )

    /** RFC 9728 metadata for each of [resources], at `/.well-known/oauth-protected-resource` + the resource's path. */
    public val protectedResourceMetadata: HttpHandler<PathSpec0> =
        path.path(".well-known").path("oauth-protected-resource").any.get bind HttpHandler { request ->
            val resourcePath = "/" + (request.path.trailingSegments?.joinToString("/") ?: "")
            val resource = resources.find { it.path == resourcePath } ?: throw NotFoundException()
            jsonResponse(
                ProtectedResourceMetadata.serializer(), ProtectedResourceMetadata(
                    resource = generalSettings().publicUrl + resource.path,
                    authorization_servers = listOf(generalSettings().publicUrl),
                    scopes_supported = resource.scopes.toList(),
                    resource_name = resource.name,
                )
            )
        }

    public val jwks: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, JwksResponse> =
        oauth.path("jwks").get bind ApiHttpHandler(
            summary = "JSON Web Key Set",
            description = "The public keys that verify ID tokens.",
            auth = noAuth,
            implementation = { _: Unit -> JwksResponse(listOf(signingKey().jwk())) }
        )

    // ----- Authorization, driven by the frontend ----------------------------------------------------------------

    private val authorizationErrorCases = listOf(
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing or invalid parameters"),
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Unknown client or unregistered redirect URI; never redirect"),
        LSError(http = 400, detail = OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE, message = "Unsupported response type"),
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_SCOPE, message = "Invalid or disallowed scope"),
        LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_TARGET, message = "Unknown resource"),
    )

    /**
     * Checks an authorization request for the logged-in user, returning a redirect straight away if they already
     * consented and the redirect URI identifies the client, or what the consent screen needs.
     *
     * Only an exact match of a registered `https` redirect identifies the client: the code can only reach that site.
     * Any local process can listen on a loopback port, and any app can claim a custom scheme, so a request with such
     * a redirect could come from an impersonator, and is always shown to the user (RFC 8252 §8.6).
     */
    public val authorizePrepare: ApiHttpHandler<PathSpec0, USER, AuthorizationRequest, AuthorizePrepareResponse> =
        oauth.path("authorize").path("prepare").post bind explicitApiHttpHandler(
            summary = "Prepare Authorization",
            description = "Checks an OAuth authorization request for the logged-in user and says whether consent is needed.",
            auth = principal.require(),
            inputType = AuthorizationRequest.serializer(),
            outputType = AuthorizePrepareResponse.serializer(),
            errorCases = authorizationErrorCases,
            implementation = { request: AuthorizationRequest ->
                val valid = validateAuthorization(request)
                val userId = encodeUserId(auth.id)
                val redirectIdentifiesClient = request.redirect_uri.startsWith("https://") && request.redirect_uri in valid.client.redirectUris
                val consented = redirectIdentifiesClient && consents()
                    .find((UserConsent.userId eq userId) and (UserConsent.clientId eq valid.client._id))
                    .toList()
                    .any { it.scopes.containsAll(valid.scopes) }
                if (consented) AuthorizePrepareResponse(redirectUri = issueCode(request, valid, userId, valid.scopes))
                else AuthorizePrepareResponse(
                    consent = ConsentRequest(
                        clientId = valid.client._id,
                        clientName = valid.client.niceName,
                        clientLogo = valid.client.logo,
                        redirectUri = request.redirect_uri,
                        resource = request.resource,
                        requestedScopes = valid.scopes,
                        scopeDescriptions = valid.scopes.associateWith { scopeDescriptions[it] ?: it },
                    )
                )
            }
        )

    /**
     * Records the user's consent, replacing any earlier one for the client, and returns the redirect that carries the
     * authorization code.
     */
    public val authorizeApprove: ApiHttpHandler<PathSpec0, USER, AuthorizeApproveRequest, AuthorizeResult> =
        oauth.path("authorize").path("approve").post bind explicitApiHttpHandler(
            summary = "Approve Authorization",
            description = "Records the user's consent, replacing any earlier one for the client, and returns the redirect carrying the authorization code.",
            auth = principal.require(),
            inputType = AuthorizeApproveRequest.serializer(),
            outputType = AuthorizeResult.serializer(),
            errorCases = authorizationErrorCases,
            implementation = { approve: AuthorizeApproveRequest ->
                val valid = validateAuthorization(approve.request)
                val granted = approve.grantedScopes
                if (granted.isEmpty() || !valid.scopes.containsAll(granted)) throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_SCOPE, message = "Granted scopes must be some of those requested"
                )
                val userId = encodeUserId(auth.id)
                // Replaced, not added to, so a scope the user unticked this time isn't granted silently next time.
                // Deleting first also removes the several rows that approvals used to add.
                consents().deleteMany((UserConsent.userId eq userId) and (UserConsent.clientId eq valid.client._id))
                consents().insertOne(
                    UserConsent(userId = userId, clientId = valid.client._id, scopes = granted, grantedAt = now())
                )
                serverRuntime.reportAuthEvent(
                    AuthEvent.ConsentGranted(principal.name, auth.id.toString(), valid.client._id, granted, request)
                )
                AuthorizeResult(redirectUri = issueCode(approve.request, valid, userId, granted))
            }
        )

    /** Returns the client's redirect for a user who declined.  The frontend must not build that redirect itself. */
    public val authorizeDeny: ApiHttpHandler<PathSpec0, USER, AuthorizationRequest, AuthorizeResult> =
        oauth.path("authorize").path("deny").post bind explicitApiHttpHandler(
            summary = "Deny Authorization",
            description = "Returns the redirect that tells the client the user declined.",
            auth = principal.require(),
            inputType = AuthorizationRequest.serializer(),
            outputType = AuthorizeResult.serializer(),
            errorCases = authorizationErrorCases,
            implementation = { request: AuthorizationRequest ->
                registeredClient(request.client_id, request.redirect_uri)
                AuthorizeResult(
                    appendQuery(
                        request.redirect_uri,
                        "error" to OAuth2ErrorCodes.ACCESS_DENIED,
                        "state" to request.state,
                        "iss" to generalSettings().publicUrl,
                    )
                )
            }
        )

    public val consentList: ApiHttpHandler<PathSpec0, USER, Unit, List<ClientConsent>> =
        oauth.path("consents").get bind explicitApiHttpHandler(
            summary = "List Consents",
            description = "The clients the user has consented to, and the scopes of each.",
            auth = principal.require(),
            inputType = Unit.serializer(),
            outputType = ListSerializer(ClientConsent.serializer()),
            implementation = { _: Unit ->
                val granted = consents().find(UserConsent.userId eq encodeUserId(auth.id)).toList()
                val clientsById = clients().find(OauthClient._id inside granted.map { it.clientId }).toList().associateBy { it._id }
                granted.mapNotNull { consent ->
                    val client = clientsById[consent.clientId] ?: return@mapNotNull null
                    ClientConsent(
                        clientId = client._id,
                        clientName = client.niceName,
                        clientLogo = client.logo,
                        scopes = consent.scopes,
                        grantedAt = consent.grantedAt,
                    )
                }
            }
        )

    /**
     * Withdraws the user's consent to a client and ends the user's sessions issued to it, so its refresh tokens stop
     * working and its next authorization asks again.  Access tokens already issued last until they expire, as for any
     * ended session, unless the session manager checks token invalidation.
     */
    public val withdrawConsent: ApiHttpHandler<PathSpec1<String>, USER, Unit, Unit> =
        oauth.path("consents").arg<String>("clientId").delete bind explicitApiHttpHandler(
            summary = "Withdraw Consent",
            description = "Withdraws the user's consent to a client and ends the user's sessions issued to it.",
            auth = principal.require(),
            inputType = Unit.serializer(),
            outputType = Unit.serializer(),
            implementation = { _: Unit ->
                val clientId = request.arg1
                val withdrawn = consents().deleteMany((UserConsent.userId eq encodeUserId(auth.id)) and (UserConsent.clientId eq clientId))
                sessions.sessionInfo.table().updateMany(
                    (sessionLens.subjectId eq auth.id) and (sessionLens.oauthClient eq clientId) and (sessionLens.terminated eq null),
                    Modification(sessionLens) { it.terminated assign now() },
                )
                if (withdrawn.isNotEmpty()) {
                    serverRuntime.reportAuthEvent(AuthEvent.ConsentWithdrawn(principal.name, auth.id.toString(), clientId, request))
                }
                Unit
            }
        )

    private class ValidAuthorization(val client: OauthClient, val scopes: Set<String>, val resource: ProtectedResource?)

    /** Unknown clients and unregistered redirects are `invalid_client`: the frontend must show an error, never redirect. */
    context(server: ServerRuntime)
    private suspend fun registeredClient(clientId: String, redirectUri: String): OauthClient {
        val client = clients().get(clientId) ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Unknown client"
        )
        if (client.redirectUris.none { redirectUriMatches(it, redirectUri) }) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "redirect_uri is not registered for this client"
        )
        return client
    }

    context(server: ServerRuntime)
    private suspend fun validateAuthorization(request: AuthorizationRequest): ValidAuthorization {
        val client = registeredClient(request.client_id, request.redirect_uri)
        if (request.response_type != "code") throw BadRequestException(
            detail = OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE, message = "Only the 'code' response type is supported"
        )
        if (request.code_challenge_method != "S256" || request.code_challenge?.matches(s256Challenge) != true) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "PKCE with an S256 code_challenge is required"
        )
        val resource = request.resource?.let { findResource(it) }

        // Refresh tokens depend on the client, not on this scope, which MCP clients may or may not send.
        val asked = request.scope.split(' ').filter { it.isNotBlank() }.toSet() - OpenIdScopes.OFFLINE_ACCESS
        val allowed = resource?.scopes?.intersect(client.scopes) ?: client.scopes
        val scopes = asked.ifEmpty { allowed }
        if (scopes.isEmpty() || !allowed.containsAll(scopes)) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_SCOPE, message = "The client may not request these scopes"
        )
        return ValidAuthorization(client, scopes, resource)
    }

    context(server: ServerRuntime)
    private fun findResource(uri: String): ProtectedResource =
        resources.find { sameResource(generalSettings().publicUrl + it.path, uri) } ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_TARGET, message = "Unknown resource"
        )

    context(server: ServerRuntime)
    private suspend fun issueCode(
        request: AuthorizationRequest,
        valid: ValidAuthorization,
        userId: String,
        scopes: Set<String>,
    ): String {
        val code = randomToken()
        cache().set(
            "oauth-code:$code",
            AuthorizationCode(
                clientId = valid.client._id,
                userId = userId,
                redirectUri = request.redirect_uri,
                scopes = scopes,
                resource = valid.resource?.let { generalSettings().publicUrl + it.path },
                nonce = request.nonce,
                codeChallenge = request.code_challenge!!,
                authTime = now(),
            ),
            codeLifetime,
        )
        return appendQuery(
            request.redirect_uri,
            "code" to code,
            "state" to request.state,
            "iss" to generalSettings().publicUrl,
        )
    }

    // ----- Token endpoints, called by clients --------------------------------------------------------------------

    /** Exchanges an authorization code or a refresh token for tokens.  The refresh token rotates on each use. */
    public val token: HttpHandler<PathSpec0> = oauth.path("token").post bind HttpHandler { request ->
        oauthResponse(TokenResponse.serializer()) {
            val body = request.parseBody(TokenRequest.serializer())
            val client = authenticateClient(request, body.client_id, body.client_secret)
            when (body.grant_type) {
                GrantTypes.AUTHORIZATION_CODE -> redeemCode(client, body)
                GrantTypes.REFRESH_TOKEN -> refresh(client, body, request)
                else -> throw BadRequestException(
                    detail = OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, message = "Unsupported grant type"
                )
            }
        }
    }

    /** Revokes a token issued to the calling client by ending its session (RFC 7009).  Always succeeds otherwise. */
    public val revoke: HttpHandler<PathSpec0> = oauth.path("revoke").post bind HttpHandler { request ->
        oauthResponse(Unit.serializer()) {
            val body = request.parseBody(RevocationRequest.serializer())
            val client = authenticateClient(request, body.client_id, body.client_secret)
            val session = resolveToken(body.token)
            if (session != null && session.oauthClient == client._id) terminate(session._id)
        }
    }

    /** Says whether a token issued to the calling client is active (RFC 7662). */
    public val introspect: HttpHandler<PathSpec0> = oauth.path("introspect").post bind HttpHandler { request ->
        oauthResponse(IntrospectionResponse.serializer()) {
            val body = request.parseBody(IntrospectionRequest.serializer())
            val client = authenticateClient(request, body.client_id, body.client_secret)
            val session = resolveToken(body.token)
            if (session == null || session.oauthClient != client._id || !session.isLive()) IntrospectionResponse(active = false)
            else IntrospectionResponse(
                active = true,
                scope = session.scopes.joinToString(" ") { it.asString },
                client_id = client._id,
                username = session.subjectId.toString(),
                sub = session.subjectId.toString(),
                token_type = "Bearer",
                iat = session.createdAt.epochSeconds,
                exp = session.expires?.epochSeconds,
            )
        }
    }

    context(server: ServerRuntime)
    private suspend fun <T> HttpRequest<*>.parseBody(serializer: KSerializer<T>): T =
        (body ?: throw BadRequestException(detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing body"))
            .parse(serializer)

    /** Runs a token endpoint, answering failures with an RFC 6749 error body that never carries internals. */
    context(server: ServerRuntime)
    private suspend fun <T> oauthResponse(serializer: KSerializer<T>, action: suspend () -> T): HttpResponse {
        val noStore = HttpHeaders { add(HttpHeader.CacheControl, "no-store") }
        return try {
            jsonResponse(serializer, action(), headers = noStore)
        } catch (e: HttpStatusException) {
            // Only our own errors carry a description; a parse failure's message names internal classes.
            val ours = e.detail in oauthErrorCodes
            val error = if (ours) e.detail else OAuth2ErrorCodes.INVALID_REQUEST
            val unauthorized = error == OAuth2ErrorCodes.INVALID_CLIENT
            jsonResponse(
                OAuth2Error.serializer(),
                OAuth2Error(error, if (ours) e.message else "Malformed request"),
                status = if (unauthorized) HttpStatus.Unauthorized else HttpStatus.BadRequest,
                headers = if (unauthorized) HttpHeaders {
                    add(HttpHeader.CacheControl, "no-store")
                    add(HttpHeader.WWWAuthenticate, "Basic realm=\"oauth\"")
                } else noStore,
            )
        } catch (e: Exception) {
            logger.error(e) { "OAuth token endpoint failed" }
            jsonResponse(OAuth2Error.serializer(), OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR), status = HttpStatus.InternalServerError, headers = noStore)
        }
    }

    /**
     * Identifies the client from HTTP Basic or the body.  A client with an active secret must present it; one without
     * is public and relies on PKCE.
     */
    context(server: ServerRuntime)
    private suspend fun authenticateClient(request: HttpRequest<*>, bodyId: String?, bodySecret: String?): OauthClient {
        val invalidClient = BadRequestException(detail = OAuth2ErrorCodes.INVALID_CLIENT, message = "Client authentication failed")
        val basic = request.headers[HttpHeader.Authorization]?.root
            ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
            ?.let {
                val decoded = try {
                    Base64.decode(it.substring(6).trim()).decodeToString()
                } catch (e: IllegalArgumentException) {
                    throw invalidClient
                }
                // RFC 6749 §2.3.1: both halves are form-encoded before joining.
                URLDecoder.decode(decoded.substringBefore(':'), Charsets.UTF_8) to
                    URLDecoder.decode(decoded.substringAfter(':', ""), Charsets.UTF_8)
            }
        if (basic != null && bodyId != null && bodyId != basic.first) throw invalidClient
        val clientId = basic?.first ?: bodyId ?: throw invalidClient
        val secret = basic?.second ?: bodySecret
        val client = clients().get(clientId) ?: throw invalidClient
        val activeSecrets = client.secrets.filter { it.disabledAt == null }
        if (activeSecrets.isNotEmpty() && (secret == null || activeSecrets.none { secret.checkAgainstHash(it.secretHash) }))
            throw invalidClient
        return client
    }

    context(server: ServerRuntime)
    private suspend fun redeemCode(client: OauthClient, request: TokenRequest): TokenResponse {
        val invalidGrant = BadRequestException(detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Invalid authorization code")
        val code = request.code ?: throw BadRequestException(detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing code")
        // Atomic on every cache, unlike getAndRemove, so two concurrent redemptions can't both succeed.
        if (cache().add("oauth-code-claimed:$code", 1, codeLifetime) != 1) throw invalidGrant
        val authCode = cache().getAndRemove<AuthorizationCode>("oauth-code:$code") ?: throw invalidGrant
        if (authCode.clientId != client._id) throw invalidGrant
        if (request.redirect_uri != authCode.redirectUri) throw invalidGrant
        val verifier = request.code_verifier?.takeIf { it.matches(codeVerifier) } ?: throw invalidGrant
        if (!MessageDigest.isEqual(s256(verifier).encodeToByteArray(), authCode.codeChallenge.encodeToByteArray())) throw invalidGrant
        if (request.resource != null && (authCode.resource == null || !sameResource(authCode.resource, request.resource!!))) throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_TARGET, message = "resource does not match the authorization"
        )

        val user = principal.fetch(server.internalSerialization.json.decodeFromString(principal.idSerializer, authCode.userId))
        val (session, refreshToken) = sessions.newSession(
            subject = user,
            label = client.niceName,
            expires = if (client.allowRefreshTokens) sessions.sessionExpiration(user) else now() + sessions.tokenFormat().expiration,
            stale = sessions.sessionStaleAfter(user)?.let { now() + it },
            scopes = authCode.scopes.mapTo(HashSet()) { GrantedScope(it) },
            oauthClient = client._id,
            audience = authCode.resource,
            authenticatedAt = authCode.authTime,
        )
        return tokenResponse(
            client, user, session,
            refreshToken = refreshToken.takeIf { client.allowRefreshTokens },
            nonce = authCode.nonce,
            authTime = authCode.authTime,
        )
    }

    /**
     * Swaps a refresh token for new tokens through [SessionManager.rotateRefreshToken], after checking it was issued
     * to [client], still fits the client's scopes, and was issued for the requested resource.
     */
    context(server: ServerRuntime)
    private suspend fun refresh(client: OauthClient, request: TokenRequest, httpRequest: HttpRequest<*>): TokenResponse {
        val invalidGrant = BadRequestException(detail = OAuth2ErrorCodes.INVALID_GRANT, message = "Invalid refresh token")
        val presented = RefreshToken(request.refresh_token ?: throw BadRequestException(
            detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "Missing refresh_token"
        ))
        if (!presented.valid || presented.type != principal.name) throw invalidGrant
        val sessionId = try {
            presented._id
        } catch (e: IllegalArgumentException) {
            throw invalidGrant
        }
        // Read before rotating, so a token presented by the wrong client, or for scopes it may no longer have, is
        // refused without rotating it or ending its session.
        val session = sessions.sessionInfo.table().get(sessionId) ?: throw invalidGrant
        if (session.oauthClient != client._id || !client.allowRefreshTokens) throw invalidGrant
        val scopes = session.scopes.map { it.asString }
        // The client's allowed scopes may have shrunk since.
        if (!client.scopes.containsAll(scopes)) throw invalidGrant
        if (request.resource != null && session.audience?.let { sameResource(it, request.resource!!) } != true)
            throw BadRequestException(detail = OAuth2ErrorCodes.INVALID_TARGET, message = "resource does not match the authorization")

        val rotated = try {
            sessions.rotateRefreshToken(presented, httpRequest)
        } catch (e: UnauthorizedException) {
            throw invalidGrant
        } catch (e: ForbiddenException) {
            throw invalidGrant
        } ?: throw invalidGrant
        return tokenResponse(
            client, rotated.subject, rotated.session,
            refreshToken = rotated.refreshToken,
            nonce = null,
            authTime = rotated.session.authenticatedAt ?: rotated.session.createdAt,
        )
    }

    context(server: ServerRuntime)
    private suspend fun tokenResponse(
        client: OauthClient,
        user: USER,
        session: Session<USER, ID>,
        refreshToken: RefreshToken?,
        nonce: String?,
        authTime: Instant,
    ): TokenResponse {
        val auth = with(sessions) { session.toAuth(user) }.apply { precache(principal.precache) }
        val scopes = session.scopes.map { it.asString }
        return TokenResponse(
            access_token = sessions.tokenFormat().create(principal, auth),
            expires_in = sessions.tokenFormat().expiration.inWholeSeconds,
            refresh_token = refreshToken?.string,
            id_token = if (OpenIdScopes.OPENID in scopes) idToken(user, client._id, nonce, authTime) else null,
            scope = scopes.joinToString(" "),
        )
    }

    context(server: ServerRuntime)
    private suspend fun idToken(user: USER, clientId: String, nonce: String?, authTime: Instant): String {
        val issuedAt = now().epochSeconds
        val claims = getUserClaims(user).copy(
            iss = generalSettings().publicUrl,
            aud = clientId,
            iat = issuedAt,
            exp = issuedAt + sessions.tokenFormat().expiration.inWholeSeconds,
            nonce = nonce,
            auth_time = authTime.epochSeconds,
        )
        val key = signingKey()
        val json = Json(server.internalSerialization.json) { encodeDefaults = true; explicitNulls = false }
        val signingInput = base64Url.encode(json.encodeToString(JoseHeader.serializer(), JoseHeader(alg = "RS256", kid = key.jwk().kid)).encodeToByteArray()) +
            "." + base64Url.encode(json.encodeToString(IdTokenClaims.serializer(), claims).encodeToByteArray())
        return signingInput + "." + base64Url.encode(key.sign(signingInput.encodeToByteArray()))
    }

    // ----- OpenID Connect resource endpoints -------------------------------------------------------------------------

    public val userinfo: ApiHttpHandler<PathSpec0, USER, Unit, UserInfoResponse> =
        oauth.path("userinfo").get bind explicitApiHttpHandler(
            summary = "UserInfo",
            description = "OpenID Connect claims for the bearer token, filtered by its scopes.",
            auth = principal.require(scope = RequiredScope(OpenIdScopes.OPENID)),
            inputType = Unit.serializer(),
            outputType = UserInfoResponse.serializer(),
            implementation = { _: Unit ->
                val claims = getUserClaims(auth.fetch())
                val granted = auth.scopes.map { it.asString }.toSet()
                val profile = OpenIdScopes.PROFILE in granted
                val email = OpenIdScopes.EMAIL in granted
                val address = OpenIdScopes.ADDRESS in granted
                val phone = OpenIdScopes.PHONE in granted
                UserInfoResponse(
                    sub = claims.sub,
                    name = claims.name.takeIf { profile },
                    given_name = claims.given_name.takeIf { profile },
                    family_name = claims.family_name.takeIf { profile },
                    middle_name = claims.middle_name.takeIf { profile },
                    nickname = claims.nickname.takeIf { profile },
                    preferred_username = claims.preferred_username.takeIf { profile },
                    profile = claims.profile.takeIf { profile },
                    picture = claims.picture.takeIf { profile },
                    website = claims.website.takeIf { profile },
                    gender = claims.gender.takeIf { profile },
                    birthdate = claims.birthdate.takeIf { profile },
                    zoneinfo = claims.zoneinfo.takeIf { profile },
                    locale = claims.locale.takeIf { profile },
                    updated_at = claims.updated_at.takeIf { profile },
                    email = claims.email.takeIf { email },
                    email_verified = claims.email_verified.takeIf { email },
                    address = claims.address.takeIf { address },
                    phone_number = claims.phone_number.takeIf { phone },
                    phone_number_verified = claims.phone_number_verified.takeIf { phone },
                )
            }
        )

    /** OpenID Connect RP-initiated logout: ends the bearer token's session and checks where to send the browser. */
    public val endSession: ApiHttpHandler<PathSpec0, USER, EndSessionRequest, EndSessionResponse> =
        oauth.path("end_session").post bind explicitApiHttpHandler(
            summary = "End Session",
            description = "Ends the access token's session and returns the checked post-logout redirect.",
            auth = principal.require(scope = RequiredScope(OpenIdScopes.OPENID)),
            inputType = EndSessionRequest.serializer(),
            outputType = EndSessionResponse.serializer(),
            errorCases = listOf(
                LSError(http = 400, detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "post_logout_redirect_uri is not registered for this client"),
            ),
            implementation = { request: EndSessionRequest ->
                val sessionId = auth.sessionId?.let(Uuid::parse) ?: throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "No session behind this token"
                )
                val session = sessions.sessionInfo.table().get(sessionId)
                terminate(sessionId)
                val redirect = request.post_logout_redirect_uri ?: return@explicitApiHttpHandler EndSessionResponse()
                val client = session?.oauthClient?.let { clients().get(it) }
                if (client == null || redirect !in client.postLogoutRedirectUris) throw BadRequestException(
                    detail = OAuth2ErrorCodes.INVALID_REQUEST, message = "post_logout_redirect_uri is not registered for this client"
                )
                EndSessionResponse(redirectUri = appendQuery(redirect, "state" to request.state))
            }
        )

    // ----- Sessions ----------------------------------------------------------------------------------------------

    context(server: ServerRuntime)
    private fun Session<USER, ID>.isLive(): Boolean =
        terminated == null && (expires ?: Instant.DISTANT_FUTURE) > now() && (stale ?: Instant.DISTANT_FUTURE) > now()

    /** Ending a session also ends its sub-sessions; [SessionManager] does that on any update to its table. */
    context(server: ServerRuntime)
    private suspend fun terminate(id: Uuid) {
        sessions.sessionInfo.table().updateOneById(id, Modification(sessionLens) { it.terminated assign now() })
    }

    /** The session behind an access or refresh token, or `null` if the token isn't one. */
    context(server: ServerRuntime)
    private suspend fun resolveToken(token: String): Session<USER, ID>? {
        val refresh = RefreshToken(token)
        if (refresh.valid) return sessions.refreshTokenSession(refresh)
        val auth = try {
            sessions.tokenFormat().read(principal, token)
        } catch (e: TokenException) {
            null
        } ?: return null
        val id = auth.sessionId?.let(Uuid::parseOrNull) ?: return null
        return sessions.sessionInfo.table().get(id)
    }

    context(server: ServerRuntime)
    private fun encodeUserId(id: ID): String = server.internalSerialization.json.encodeToString(principal.idSerializer, id)
}

/**
 * Whether [requested] may be used as a redirect for a client that registered [registered]: an exact match, or for a
 * loopback URI over plain HTTP, a match on everything but the port, since native clients pick a free port each time
 * (RFC 8252 §7.3).
 */
internal fun redirectUriMatches(registered: String, requested: String): Boolean {
    if (registered == requested) return true
    val reg = parseUri(registered) ?: return false
    val req = parseUri(requested) ?: return false
    return reg.scheme == "http" && req.scheme == "http" &&
        reg.host in loopbackHosts && reg.host == req.host &&
        reg.rawUserInfo == null && req.rawUserInfo == null &&
        reg.rawPath == req.rawPath && reg.rawQuery == req.rawQuery &&
        req.rawFragment == null
}

/** MCP asks servers to accept resource URIs whose scheme and host differ in case (RFC 8707 §2, RFC 3986 §6.2.2.1). */
internal fun sameResource(canonical: String, requested: String): Boolean {
    val a = parseUri(canonical) ?: return false
    val b = parseUri(requested) ?: return false
    return a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) &&
        a.port == b.port && a.rawPath == b.rawPath && b.rawQuery == null && b.rawFragment == null && b.rawUserInfo == null
}

private val loopbackHosts = setOf("127.0.0.1", "[::1]", "localhost")

private fun parseUri(uri: String): URI? = try {
    URI(uri)
} catch (e: java.net.URISyntaxException) {
    null
}

@OptIn(ExperimentalOauthProviderApi::class)
private val oauthErrorCodes = setOf(
    OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.INVALID_CLIENT, OAuth2ErrorCodes.INVALID_GRANT,
    OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, OAuth2ErrorCodes.INVALID_SCOPE,
    OAuth2ErrorCodes.INVALID_TARGET,
)

// RFC 7636: an S256 challenge is 43 base64url characters; a verifier is 43 to 128 unreserved characters.
private val s256Challenge = Regex("[A-Za-z0-9_-]{43}")
private val codeVerifier = Regex("[A-Za-z0-9._~-]{43,128}")

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

internal fun s256(verifier: String): String =
    base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))

private fun randomToken(): String = base64Url.encode(CryptographyRandom.nextBytes(32))

private fun appendQuery(base: String, vararg params: Pair<String, String?>): String =
    params.filter { it.second != null }.fold(base) { url, (key, value) ->
        url + (if ('?' in url) '&' else '?') + key + "=" + URLEncoder.encode(value, Charsets.UTF_8)
    }

context(server: ServerRuntime)
private fun <T> jsonResponse(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY,
): HttpResponse = HttpResponse(
    status = status,
    headers = headers,
    body = TypedData.text(server.externalSerialization.jsonWithoutExplicitNulls.encodeToString(serializer, value), MediaType.Application.Json),
)

@Serializable
private data class JoseHeader(val alg: String, val kid: String, val typ: String = "JWT")

/** An authorization code's grant, held in the cache until it is redeemed once or expires. */
@Serializable
internal data class AuthorizationCode(
    val clientId: String,
    val userId: String,
    val redirectUri: String,
    val scopes: Set<String>,
    val resource: String?,
    val nonce: String?,
    val codeChallenge: String,
    val authTime: Instant,
)
