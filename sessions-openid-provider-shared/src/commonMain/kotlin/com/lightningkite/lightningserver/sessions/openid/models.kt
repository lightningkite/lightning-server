package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/*
 * Wire models for the Lightning Server OpenID Connect provider.
 *
 * Field names deliberately use the snake_case spelling from the OAuth 2.0 / OpenID Connect
 * specs (RFC 6749, OpenID Connect Core 1.0) because they are serialized directly onto the
 * wire that third-party relying parties consume. The internal Lightning Server models that
 * back them (sessions, scopes) keep their normal Kotlin naming.
 *
 * Note on token storage: access and refresh tokens are NOT modeled here. They are ordinary
 * Lightning Server sessions (see SessionManager) tagged with the issuing client, which makes
 * the session table the single source of truth for issuance, validity, and revocation.
 */

/**
 * OpenID Provider discovery metadata, published at `/.well-known/openid-configuration`
 * per OpenID Connect Discovery 1.0.
 */
@Serializable
public data class ProviderMetadata(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val userinfo_endpoint: String? = null,
    val jwks_uri: String,
    val response_types_supported: List<String>,
    val subject_types_supported: List<String>,
    val id_token_signing_alg_values_supported: List<String>,
    val scopes_supported: List<String>? = null,
    val token_endpoint_auth_methods_supported: List<String>? = null,
    val claims_supported: List<String>? = null,
    val code_challenge_methods_supported: List<String>? = null,
    val grant_types_supported: List<String>? = null,
)

/** JSON Web Key Set response — the public keys clients use to verify ID token signatures. */
@Serializable
public data class JwksResponse(
    val keys: List<JsonWebKey>,
)

/**
 * A single public key in JWK format.
 *
 * @property kty Key type ("RSA" or "EC")
 * @property use Public key use ("sig")
 * @property kid Key ID, matched against the JWT header `kid`
 * @property alg Signing algorithm (e.g. "RS256", "ES256")
 * @property n RSA modulus (base64url) — RSA keys only
 * @property e RSA public exponent (base64url) — RSA keys only
 * @property crv EC curve name (e.g. "P-256") — EC keys only
 * @property x EC public key X coordinate (base64url) — EC keys only
 * @property y EC public key Y coordinate (base64url) — EC keys only
 */
@Serializable
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

/** OAuth 2.0 / OpenID Connect token response returned from the token endpoint. */
@Serializable
public data class TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int,
    val refresh_token: String? = null,
    val id_token: String? = null,
    val scope: String? = null,
)

/** Parameters POSTed to the token endpoint. */
@Serializable
public data class TokenRequest(
    val grant_type: String,
    val code: String? = null,
    val redirect_uri: String? = null,
    val client_id: String,
    val client_secret: String? = null,
    val code_verifier: String? = null,
    val refresh_token: String? = null,
)

/**
 * Parameters of an authorization request. In Lightning Server's API-only model this is supplied
 * by the host application's frontend (which reads them off its own authorization route) and sent
 * to the [authorize prepare/approve] endpoints, rather than being parsed from a browser redirect
 * by the server itself.
 */
@Serializable
public data class AuthorizationRequest(
    val response_type: String,
    val client_id: String,
    val redirect_uri: String,
    val scope: String,
    val state: String? = null,
    val nonce: String? = null,
    val code_challenge: String? = null,
    val code_challenge_method: String? = null,
)

/**
 * Result of `authorize/prepare`. Exactly one field is non-null:
 * - [redirectUri] set: authorization succeeded immediately (trusted client or existing consent);
 *   the frontend should redirect the browser there.
 * - [consent] set: explicit user consent is required; the frontend should render a consent screen
 *   and then call `authorize/approve`.
 */
@Serializable
public data class AuthorizePrepareResponse(
    val redirectUri: String? = null,
    val consent: ConsentRequest? = null,
)

/** Details a frontend needs to render a consent screen. */
@Serializable
public data class ConsentRequest(
    val clientId: String,
    val clientName: String,
    val clientLogo: String? = null,
    val requestedScopes: Set<String>,
    val scopeDescriptions: Map<String, String>,
)

/** Sent to `authorize/approve` once the user has granted (a subset of) the requested scopes. */
@Serializable
public data class AuthorizeApproveRequest(
    val request: AuthorizationRequest,
    val grantedScopes: Set<String>,
)

/** Where the frontend should redirect the browser to hand the authorization code back to the client. */
@Serializable
public data class AuthorizeResult(
    val redirectUri: String,
)

/** Standard OpenID Connect UserInfo claims, filtered by the scopes granted to the access token. */
@Serializable
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
public data class Address(
    val formatted: String? = null,
    val street_address: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val postal_code: String? = null,
    val country: String? = null,
)

/**
 * Claims for an OpenID Connect ID token (a signed JWT). The provider sets [iss], [aud], [exp],
 * [iat], [nonce], and [auth_time]; the host application supplies the identity claims via its
 * `getUserClaims` function.
 */
@Serializable
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
 * Authorization code held briefly in cache between the authorize and token endpoints.
 * Single-use and short-lived.
 */
@Serializable
public data class AuthorizationCode(
    val clientId: String,
    val userId: String,
    val redirectUri: String,
    val scope: String,
    val nonce: String? = null,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    val authTime: Long,
    val createdAt: Instant,
)

/**
 * Record that a user granted a client access to a set of scopes, so the consent screen is not
 * shown again for the same (user, client, scopes).
 */
@GenerateDataClassPaths
@Serializable
public data class UserConsent(
    override val _id: Uuid = Uuid.random(),
    val userId: String,
    val clientId: String,
    val scopes: Set<String>,
    val grantedAt: Instant,
    val expiresAt: Instant? = null,
) : HasId<Uuid>

/**
 * A registered relying-party application.
 *
 * @property _id The client id, used publicly in OAuth flows
 * @property niceName Human-readable name shown on the consent screen
 * @property logo Optional logo URL for the consent screen
 * @property scopes Scopes this client is permitted to request
 * @property secrets Hashed client secrets (multiple to allow zero-downtime rotation)
 * @property redirectUris Exact-match allowlist of redirect URIs
 * @property postLogoutRedirectUris Exact-match allowlist of post-logout redirect URIs (end session)
 * @property trusted If true, the consent screen is skipped (first-party apps)
 * @property requirePkce Require PKCE even for confidential clients
 * @property allowRefreshTokens Whether this client may receive refresh tokens (offline_access)
 */
@GenerateDataClassPaths
@Serializable
public data class OauthClient(
    override val _id: String,
    val niceName: String,
    val logo: String? = null,
    val scopes: Set<String> = setOf(),
    val secrets: Set<OauthClientSecret> = setOf(),
    val redirectUris: Set<String> = setOf(),
    val postLogoutRedirectUris: Set<String> = setOf(),
    val trusted: Boolean = false,
    val requirePkce: Boolean = false,
    val allowRefreshTokens: Boolean = true,
) : HasId<String>

/**
 * A client secret. Stored hashed; the plaintext is shown only once at creation. A secret can be
 * disabled (rather than deleted) to support rotation.
 *
 * @property createdAt When the secret was generated.
 * @property masked A non-sensitive recognition hint (the last few characters of the plaintext) shown
 *   to maintainers so they can tell which secret a record corresponds to when rotating — e.g. matching
 *   it against the value pasted into a relying party. Far too little to reconstruct the secret.
 * @property secretHash Salted hash of the secret; the plaintext is never stored.
 * @property disabledAt When the secret was disabled, or null while it is still active.
 */
@Serializable
public data class OauthClientSecret(
    val createdAt: Instant,
    val masked: String,
    val secretHash: String,
    val disabledAt: Instant? = null,
)

/**
 * Request to the end-session (RP-initiated logout) endpoint.
 *
 * @property post_logout_redirect_uri Where to send the browser after logout; must be registered
 *   (in [OauthClient.postLogoutRedirectUris]) for the client that owns the session.
 * @property state Opaque value echoed back on the post-logout redirect.
 */
@Serializable
public data class EndSessionRequest(
    val post_logout_redirect_uri: String? = null,
    val state: String? = null,
)

/**
 * Response from the end-session endpoint.
 *
 * @property redirectUri Where the frontend should send the browser after logout (null if none).
 */
@Serializable
public data class EndSessionResponse(
    val redirectUri: String? = null,
)

/**
 * Token revocation request (RFC 7009).
 *
 * Client-authenticated (client_secret_post, matching the token endpoint). Revoking either an access
 * or refresh token terminates the underlying session, which prevents further token issuance; note
 * that an already-issued access token, being self-contained, remains valid until its short expiry.
 *
 * @property token The access or refresh token to revoke
 * @property token_type_hint Optional hint: "access_token" or "refresh_token"
 */
@Serializable
public data class RevocationRequest(
    val token: String,
    val token_type_hint: String? = null,
    val client_id: String,
    val client_secret: String? = null,
)

/**
 * Token introspection request (RFC 7662).
 *
 * Client-authenticated. Lets a relying party check whether a token is currently active and read its
 * metadata.
 *
 * @property token The token to introspect
 * @property token_type_hint Optional hint: "access_token" or "refresh_token"
 */
@Serializable
public data class IntrospectionRequest(
    val token: String,
    val token_type_hint: String? = null,
    val client_id: String,
    val client_secret: String? = null,
)

/**
 * Token introspection response (RFC 7662). When the token is invalid, expired, revoked, or was not
 * issued to the requesting client, only [active] = false is returned.
 *
 * @property active Whether the token is currently active
 * @property scope Space-separated granted scopes
 * @property client_id Client the token was issued to
 * @property username Human-readable subject identifier (here, the subject id)
 * @property token_type Token type ("Bearer")
 * @property exp Expiration time (Unix seconds)
 * @property iat Issued-at time (Unix seconds)
 * @property sub Subject identifier
 */
@Serializable
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

/** OAuth 2.0 error response body. */
@Serializable
public data class OAuth2Error(
    val error: String,
    val error_description: String? = null,
    val error_uri: String? = null,
)

/** Standard OAuth 2.0 error codes (RFC 6749 §4.1.2.1, §5.2). */
public object OAuth2ErrorCodes {
    public const val INVALID_REQUEST: String = "invalid_request"
    public const val INVALID_CLIENT: String = "invalid_client"
    public const val INVALID_GRANT: String = "invalid_grant"
    public const val UNAUTHORIZED_CLIENT: String = "unauthorized_client"
    public const val UNSUPPORTED_GRANT_TYPE: String = "unsupported_grant_type"
    public const val INVALID_SCOPE: String = "invalid_scope"
    public const val ACCESS_DENIED: String = "access_denied"
    public const val UNSUPPORTED_RESPONSE_TYPE: String = "unsupported_response_type"
    public const val SERVER_ERROR: String = "server_error"
    public const val TEMPORARILY_UNAVAILABLE: String = "temporarily_unavailable"
}

/** Standard OpenID Connect scopes. */
public object OpenIdScopes {
    public const val OPENID: String = "openid"
    public const val PROFILE: String = "profile"
    public const val EMAIL: String = "email"
    public const val ADDRESS: String = "address"
    public const val PHONE: String = "phone"
    public const val OFFLINE_ACCESS: String = "offline_access"
}

/** Standard OAuth 2.0 grant types this provider supports. */
public object GrantTypes {
    public const val AUTHORIZATION_CODE: String = "authorization_code"
    public const val REFRESH_TOKEN: String = "refresh_token"
}
