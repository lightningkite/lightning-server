package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.ExperimentalOauthProviderApi
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/*
 * Wire models for the Lightning Server OAuth 2.1 / OpenID Connect provider.
 *
 * Field names use the snake_case spelling of the specs (RFC 6749, 7009, 7662, 8414, 8707, 9728, OpenID Connect Core),
 * since third-party clients read them straight off the wire.
 *
 * Access and refresh tokens are not modeled here: they belong to ordinary Lightning Server sessions tagged with the
 * client they were issued to.
 */

/**
 * Authorization server metadata (RFC 8414), served at both `/.well-known/oauth-authorization-server` and
 * `/.well-known/openid-configuration`.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class AuthorizationServerMetadata(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val revocation_endpoint: String,
    val introspection_endpoint: String,
    val userinfo_endpoint: String,
    val end_session_endpoint: String,
    val jwks_uri: String,
    val scopes_supported: List<String>,
    val response_types_supported: List<String>,
    val grant_types_supported: List<String>,
    val code_challenge_methods_supported: List<String>,
    val token_endpoint_auth_methods_supported: List<String>,
    val revocation_endpoint_auth_methods_supported: List<String>,
    val introspection_endpoint_auth_methods_supported: List<String>,
    val authorization_response_iss_parameter_supported: Boolean,
    val subject_types_supported: List<String>,
    val id_token_signing_alg_values_supported: List<String>,
    val claims_supported: List<String>,
)

/**
 * Protected resource metadata (RFC 9728), served at `/.well-known/oauth-protected-resource` followed by the
 * resource's path.  MCP clients read it to find the authorization server.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class ProtectedResourceMetadata(
    val resource: String,
    val authorization_servers: List<String>,
    val scopes_supported: List<String>,
    val bearer_methods_supported: List<String> = listOf("header"),
    val resource_name: String? = null,
)

/** JSON Web Key Set: the public keys clients use to verify ID token signatures. */
@Serializable
@ExperimentalOauthProviderApi
public data class JwksResponse(
    val keys: List<JsonWebKey>,
)

/**
 * A public key in JWK form.  RSA keys set [n] and [e]; EC keys set [crv], [x] and [y].
 */
@Serializable
@ExperimentalOauthProviderApi
public data class JsonWebKey(
    val kty: String,
    val use: String,
    val kid: String,
    val alg: String,
    val n: String? = null,
    val e: String? = null,
    val crv: String? = null,
    val x: String? = null,
    val y: String? = null,
)

/** The token endpoint's success response. */
@Serializable
@ExperimentalOauthProviderApi
public data class TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long,
    val refresh_token: String? = null,
    val id_token: String? = null,
    val scope: String,
)

/**
 * Parameters posted to the token endpoint, usually as `application/x-www-form-urlencoded`.
 *
 * [client_id] and [client_secret] may instead come in an HTTP Basic `Authorization` header.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class TokenRequest(
    val grant_type: String,
    val code: String? = null,
    val redirect_uri: String? = null,
    val client_id: String? = null,
    val client_secret: String? = null,
    val code_verifier: String? = null,
    val refresh_token: String? = null,
    val resource: String? = null,
)

/**
 * The parameters of an authorization request, which the frontend's authorization route reads from its URL and passes
 * to `authorize/prepare`, `authorize/approve` or `authorize/deny`.
 *
 * @property scope Space-separated scopes.  Empty means every scope the client may have for [resource].
 * @property resource The protected resource the token is for (RFC 8707).
 */
@Serializable
@ExperimentalOauthProviderApi
public data class AuthorizationRequest(
    val response_type: String,
    val client_id: String,
    val redirect_uri: String,
    val scope: String = "",
    val state: String? = null,
    val nonce: String? = null,
    val code_challenge: String? = null,
    val code_challenge_method: String? = null,
    val resource: String? = null,
)

/**
 * The result of `authorize/prepare`.  Exactly one field is set: [redirectUri] when the user already consented, so the
 * frontend redirects straight away, or [consent] when it must ask first.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class AuthorizePrepareResponse(
    val redirectUri: String? = null,
    val consent: ConsentRequest? = null,
)

/**
 * What a consent screen shows.  Show [redirectUri]'s host, not just [clientName], so a user can spot a client
 * pretending to be another.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class ConsentRequest(
    val clientId: String,
    val clientName: String,
    val clientLogo: String? = null,
    val redirectUri: String,
    val resource: String? = null,
    val requestedScopes: Set<String>,
    val scopeDescriptions: Map<String, String>,
)

/** Sent to `authorize/approve` with the scopes the user granted, which must be among those requested. */
@Serializable
@ExperimentalOauthProviderApi
public data class AuthorizeApproveRequest(
    val request: AuthorizationRequest,
    val grantedScopes: Set<String>,
)

/** Where the frontend sends the browser next. */
@Serializable
@ExperimentalOauthProviderApi
public data class AuthorizeResult(
    val redirectUri: String,
)

/** Standard OpenID Connect UserInfo claims, filtered by the scopes granted to the access token. */
@Serializable
@ExperimentalOauthProviderApi
public data class UserInfoResponse(
    val sub: String,
    val name: String? = null,
    val given_name: String? = null,
    val family_name: String? = null,
    val middle_name: String? = null,
    val nickname: String? = null,
    val preferred_username: String? = null,
    val profile: String? = null,
    val picture: String? = null,
    val website: String? = null,
    val email: String? = null,
    val email_verified: Boolean? = null,
    val gender: String? = null,
    val birthdate: String? = null,
    val zoneinfo: String? = null,
    val locale: String? = null,
    val phone_number: String? = null,
    val phone_number_verified: Boolean? = null,
    val address: Address? = null,
    val updated_at: Long? = null,
)

/** Physical mailing address claim. */
@Serializable
@ExperimentalOauthProviderApi
public data class Address(
    val formatted: String? = null,
    val street_address: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val postal_code: String? = null,
    val country: String? = null,
)

/**
 * Claims for an OpenID Connect ID token.  The provider sets [iss], [aud], [exp], [iat], [nonce] and [auth_time]; the
 * application supplies the identity claims.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class IdTokenClaims(
    val iss: String,
    val sub: String,
    val aud: String,
    val exp: Long,
    val iat: Long,
    val auth_time: Long? = null,
    val nonce: String? = null,
    val acr: String? = null,
    val amr: List<String>? = null,
    val azp: String? = null,
    val name: String? = null,
    val given_name: String? = null,
    val family_name: String? = null,
    val middle_name: String? = null,
    val nickname: String? = null,
    val preferred_username: String? = null,
    val profile: String? = null,
    val picture: String? = null,
    val website: String? = null,
    val email: String? = null,
    val email_verified: Boolean? = null,
    val gender: String? = null,
    val birthdate: String? = null,
    val zoneinfo: String? = null,
    val locale: String? = null,
    val phone_number: String? = null,
    val phone_number_verified: Boolean? = null,
    val address: Address? = null,
    val updated_at: Long? = null,
)

/**
 * That a user granted a client some scopes, so the consent screen isn't shown again for them while the redirect URI
 * identifies the client.  There is one per user and client; a new approval replaces it.
 */
@Queryable
@IndexSet(["userId", "clientId"])
@ExperimentalOauthProviderApi
public data class UserConsent(
    override val _id: Uuid = Uuid.random(),
    val userId: String,
    val clientId: String,
    val scopes: Set<String>,
    val grantedAt: Instant,
) : HasId<Uuid>

/** One of the user's consents, as `oauth/consents` lists them for the user to review and withdraw. */
@Serializable
@ExperimentalOauthProviderApi
public data class ClientConsent(
    val clientId: String,
    val clientName: String,
    val clientLogo: String? = null,
    val scopes: Set<String>,
    val grantedAt: Instant,
)

/**
 * A request to the OpenID end-session (RP-initiated logout) endpoint.
 *
 * @property post_logout_redirect_uri Must be one of the client's `postLogoutRedirectUris`.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class EndSessionRequest(
    val post_logout_redirect_uri: String? = null,
    val state: String? = null,
)

/** Where the frontend sends the browser after logout, if anywhere. */
@Serializable
@ExperimentalOauthProviderApi
public data class EndSessionResponse(
    val redirectUri: String? = null,
)

/** A token revocation request (RFC 7009).  Revoking either token ends the session behind it. */
@Serializable
@ExperimentalOauthProviderApi
public data class RevocationRequest(
    val token: String,
    val token_type_hint: String? = null,
    val client_id: String? = null,
    val client_secret: String? = null,
)

/** A token introspection request (RFC 7662). */
@Serializable
@ExperimentalOauthProviderApi
public data class IntrospectionRequest(
    val token: String,
    val token_type_hint: String? = null,
    val client_id: String? = null,
    val client_secret: String? = null,
)

/**
 * A token introspection response (RFC 7662).  Only [active] = false is returned for a token that is invalid, expired,
 * revoked, or not issued to the asking client.
 */
@Serializable
@ExperimentalOauthProviderApi
public data class IntrospectionResponse(
    val active: Boolean,
    val scope: String? = null,
    val client_id: String? = null,
    val username: String? = null,
    val token_type: String? = null,
    val exp: Long? = null,
    val iat: Long? = null,
    val sub: String? = null,
)

/** The OAuth error response body (RFC 6749 §5.2). */
@Serializable
@ExperimentalOauthProviderApi
public data class OAuth2Error(
    val error: String,
    val error_description: String? = null,
)

/** Standard OAuth error codes (RFC 6749 §4.1.2.1 and §5.2, RFC 8707). */
@ExperimentalOauthProviderApi
public object OAuth2ErrorCodes {
    public const val INVALID_REQUEST: String = "invalid_request"
    public const val INVALID_CLIENT: String = "invalid_client"
    public const val INVALID_GRANT: String = "invalid_grant"
    public const val UNAUTHORIZED_CLIENT: String = "unauthorized_client"
    public const val UNSUPPORTED_GRANT_TYPE: String = "unsupported_grant_type"
    public const val INVALID_SCOPE: String = "invalid_scope"
    public const val INVALID_TARGET: String = "invalid_target"
    public const val ACCESS_DENIED: String = "access_denied"
    public const val UNSUPPORTED_RESPONSE_TYPE: String = "unsupported_response_type"
    public const val SERVER_ERROR: String = "server_error"
}

/** Standard OpenID Connect scopes. */
@ExperimentalOauthProviderApi
public object OpenIdScopes {
    public const val OPENID: String = "openid"
    public const val PROFILE: String = "profile"
    public const val EMAIL: String = "email"
    public const val ADDRESS: String = "address"
    public const val PHONE: String = "phone"
    public const val OFFLINE_ACCESS: String = "offline_access"
}

/** The OAuth grant types this provider supports. */
@ExperimentalOauthProviderApi
public object GrantTypes {
    public const val AUTHORIZATION_CODE: String = "authorization_code"
    public const val REFRESH_TOKEN: String = "refresh_token"
}
