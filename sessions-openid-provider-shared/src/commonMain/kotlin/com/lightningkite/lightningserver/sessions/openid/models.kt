package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * OpenID Provider Discovery Metadata
 *
 * Published at /.well-known/openid-configuration
 * Describes the provider's configuration according to OpenID Connect Discovery 1.0
 *
 * @property issuer The issuer identifier (URL using https scheme)
 * @property authorization_endpoint URL of the Authorization Endpoint
 * @property token_endpoint URL of the Token Endpoint
 * @property userinfo_endpoint URL of the UserInfo Endpoint (optional but recommended)
 * @property jwks_uri URL of the JWKS endpoint providing public keys
 * @property response_types_supported OAuth 2.0 response types supported
 * @property subject_types_supported Subject identifier types supported
 * @property id_token_signing_alg_values_supported JWS signing algorithms supported for ID Tokens
 * @property scopes_supported OAuth scopes supported
 * @property token_endpoint_auth_methods_supported Client authentication methods at token endpoint
 * @property claims_supported OpenID Connect claims supported
 * @property code_challenge_methods_supported PKCE challenge methods supported
 * @property grant_types_supported OAuth grant types supported
 */
@Serializable
public data class ProviderMetadata(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val userinfo_endpoint: String? = null,
    val jwks_uri: String,
    val end_session_endpoint: String? = null,
    val response_types_supported: List<String>,
    val subject_types_supported: List<String>,
    val id_token_signing_alg_values_supported: List<String>,
    val scopes_supported: List<String>? = null,
    val token_endpoint_auth_methods_supported: List<String>? = null,
    val claims_supported: List<String>? = null,
    val code_challenge_methods_supported: List<String>? = null,
    val grant_types_supported: List<String>? = null,
)

/**
 * JSON Web Key Set (JWKS) response
 *
 * Contains public keys used for verifying JWT signatures
 *
 * @property keys Array of JWK (JSON Web Key) objects
 */
@Serializable
public data class JwksResponse(
    val keys: List<JsonWebKey>
)

/**
 * JSON Web Key (JWK) representation
 *
 * Represents a single cryptographic key in JWK format
 *
 * @property kty Key type (e.g., "RSA", "EC")
 * @property use Public key use (e.g., "sig" for signature)
 * @property kid Key ID for identifying which key was used
 * @property alg Algorithm intended for use with the key (e.g., "RS256")
 * @property n RSA modulus (base64url encoded)
 * @property e RSA public exponent (base64url encoded)
 */
@Serializable
public data class JsonWebKey(
    val kty: String,
    val use: String,
    val kid: String,
    val alg: String,
    val n: String? = null,  // RSA modulus
    val e: String? = null,  // RSA exponent
)

/**
 * OAuth 2.0 / OpenID Connect Token Response
 *
 * Returned from the token endpoint after successful authorization
 *
 * @property access_token The access token for accessing protected resources
 * @property token_type Type of token (typically "Bearer")
 * @property expires_in Lifetime in seconds of the access token
 * @property refresh_token Refresh token for obtaining new access tokens (optional)
 * @property id_token OpenID Connect ID Token (JWT) containing user identity (required for OpenID Connect)
 * @property scope Space-separated list of granted scopes
 */
@Serializable
public data class TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int,
    val refresh_token: String? = null,
    val id_token: String? = null,
    val scope: String? = null,
)

/**
 * Token Request parameters
 *
 * Parameters sent to the token endpoint
 *
 * @property grant_type The grant type (e.g., "authorization_code", "refresh_token")
 * @property code Authorization code (for authorization_code grant)
 * @property redirect_uri Redirect URI used in authorization request
 * @property client_id Client identifier
 * @property client_secret Client secret (for confidential clients)
 * @property code_verifier PKCE code verifier (for public clients)
 * @property refresh_token Refresh token (for refresh_token grant)
 */
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
 * Authorization Request parameters
 *
 * Parameters sent to the authorization endpoint
 *
 * @property response_type OAuth response type (e.g., "code", "id_token", "code id_token")
 * @property client_id Client identifier
 * @property redirect_uri Where to redirect after authorization
 * @property scope Space-separated list of requested scopes (must include "openid")
 * @property state Opaque value for CSRF protection
 * @property response_mode How to return the authorization response (query, fragment, form_post)
 * @property nonce String value to associate client session with ID Token (required for implicit/hybrid flows)
 * @property code_challenge PKCE code challenge
 * @property code_challenge_method PKCE challenge method ("S256" or "plain")
 * @property prompt Space-separated list of prompt values (none, login, consent, select_account)
 * @property max_age Maximum authentication age in seconds
 * @property login_hint Hint to the authorization server about the login identifier
 */
@Serializable
public data class AuthorizationRequest(
    val response_type: String,
    val client_id: String,
    val redirect_uri: String,
    val scope: String,
    val state: String? = null,
    val response_mode: String? = null,
    val nonce: String? = null,
    val code_challenge: String? = null,
    val code_challenge_method: String? = null,
    val prompt: String? = null,
    val max_age: Int? = null,
    val login_hint: String? = null,
)

/**
 * UserInfo Response
 *
 * Standard OpenID Connect user claims returned from the UserInfo endpoint
 *
 * @property sub Subject identifier (unique user ID)
 * @property name Full name
 * @property given_name Given name(s) or first name(s)
 * @property family_name Surname(s) or last name(s)
 * @property middle_name Middle name(s)
 * @property nickname Casual name
 * @property preferred_username Preferred username
 * @property profile Profile page URL
 * @property picture Profile picture URL
 * @property website Web page or blog URL
 * @property email Email address
 * @property email_verified Whether email has been verified
 * @property gender Gender
 * @property birthdate Birthday (ISO 8601:2004 YYYY-MM-DD format)
 * @property zoneinfo Time zone (e.g., "America/Los_Angeles")
 * @property locale Locale (e.g., "en-US")
 * @property phone_number Phone number
 * @property phone_number_verified Whether phone number has been verified
 * @property address Physical mailing address
 * @property updated_at Time the information was last updated
 */
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

/**
 * Physical mailing address
 *
 * @property formatted Full mailing address, formatted for display
 * @property street_address Full street address
 * @property locality City or locality
 * @property region State, province, prefecture, or region
 * @property postal_code ZIP code or postal code
 * @property country Country name
 */
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
 * ID Token Claims
 *
 * Standard claims for OpenID Connect ID Tokens (JWTs)
 * This extends the basic UserInfo with ID token specific claims
 *
 * @property iss Issuer identifier
 * @property sub Subject identifier
 * @property aud Audience(s) - client ID(s) this token is intended for
 * @property exp Expiration time (Unix timestamp in seconds)
 * @property iat Issued at time (Unix timestamp in seconds)
 * @property auth_time Time when authentication occurred
 * @property nonce Nonce value from authorization request
 * @property acr Authentication Context Class Reference
 * @property amr Authentication Methods References
 * @property azp Authorized party - client ID if different from aud
 */
@Serializable
public data class IdTokenClaims(
    val iss: String,
    val sub: String,
    val aud: String,  // Can be array in full spec, but single value for simplicity
    val exp: Long,
    val iat: Long,
    val auth_time: Long? = null,
    val nonce: String? = null,
    val acr: String? = null,
    val amr: List<String>? = null,
    val azp: String? = null,
    // User claims (can be included in ID token based on scope)
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
 * Authorization Code stored in cache
 *
 * Temporary storage for authorization codes pending token exchange
 *
 * @property code The authorization code value
 * @property clientId Client that requested this code
 * @property userId User who authorized
 * @property redirectUri Redirect URI from authorization request
 * @property scope Requested scopes
 * @property nonce Nonce value for ID token
 * @property codeChallenge PKCE code challenge
 * @property codeChallengeMethod PKCE challenge method
 * @property authTime When user authenticated
 * @property createdAt When this code was created
 */
@Serializable
public data class AuthorizationCode(
    val code: String,
    val clientId: String,
    val userId: String,  // Could be Uuid or other ID type
    val redirectUri: String,
    val scope: String,
    val nonce: String? = null,
    val codeChallenge: String? = null,
    val codeChallengeMethod: String? = null,
    val authTime: Long,
    val createdAt: Instant,
)

/**
 * Issued Token tracking (stored in cache)
 *
 * Tracks issued access and refresh tokens
 *
 * @property token The token value
 * @property tokenType Type of token (access or refresh)
 * @property userId User this token belongs to
 * @property clientId Client this token was issued to
 * @property scope Granted scopes
 * @property issuedAt When token was issued
 * @property expiresAt When token expires
 */
@Serializable
public data class IssuedToken(
    val token: String,
    val tokenType: TokenType,
    val userId: String,
    val clientId: String,
    val scope: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val nonce: String? = null,
    val authTime: Long? = null,
)

/**
 * Token type enumeration
 */
@Serializable
public enum class TokenType {
    ACCESS,
    REFRESH
}

/**
 * User Consent Record (stored in database)
 *
 * Tracks user consent for client applications to access their data
 *
 * @property _id Unique consent ID
 * @property userId User who granted consent
 * @property clientId Client that received consent
 * @property scopes Scopes that were consented to
 * @property grantedAt When consent was granted
 * @property expiresAt When consent expires (optional)
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
 * PKCE Challenge Methods
 */
@Serializable
public enum class PkceChallengeMethod {
    S256,
    plain
}

/**
 * OAuth 2.0 Error Response
 *
 * Standard error response format
 *
 * @property error Error code
 * @property error_description Human-readable error description
 * @property error_uri URI of a web page with error information
 */
@Serializable
public data class OAuth2Error(
    val error: String,
    val error_description: String? = null,
    val error_uri: String? = null,
)

/**
 * Standard OAuth 2.0 error codes
 */
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

/**
 * Standard OpenID Connect scopes
 */
public object OpenIdScopes {
    public const val OPENID: String = "openid"
    public const val PROFILE: String = "profile"
    public const val EMAIL: String = "email"
    public const val ADDRESS: String = "address"
    public const val PHONE: String = "phone"
    public const val OFFLINE_ACCESS: String = "offline_access"
}

/**
 * Standard OAuth 2.0 grant types
 */
public object GrantTypes {
    public const val AUTHORIZATION_CODE: String = "authorization_code"
    public const val REFRESH_TOKEN: String = "refresh_token"
    public const val CLIENT_CREDENTIALS: String = "client_credentials"
    public const val IMPLICIT: String = "implicit"
}

/**
 * UserInfo Request
 *
 * Request parameters for the UserInfo endpoint
 *
 * Note: This is a simplified implementation that accepts the access token
 * in the request body. Standard OpenID Connect implementations use Bearer
 * token authentication via the Authorization header.
 *
 * @property access_token The access token to validate and retrieve user info for
 */
@Serializable
public data class UserInfoRequest(
    val access_token: String
)

/**
 * Authorization Response
 *
 * Response from the authorization endpoint containing the authorization code
 *
 * This response is typically sent as a redirect to the client's redirect_uri
 * with the code and state parameters in the query string.
 *
 * @property code The authorization code to be exchanged for tokens
 * @property state The state parameter from the authorization request (for CSRF protection)
 */
@Serializable
public data class AuthorizationResponse(
    val code: String,
    val state: String? = null
)

/**
 * OAuth 2.0 Client Application
 *
 * Represents a client application that can authenticate users via this server
 * when acting as an OAuth/OpenID Connect Provider.
 *
 * This model stores client credentials and configuration for applications that integrate
 * with your server. Each client can have multiple secrets (for rotation) and multiple
 * redirect URIs.
 *
 * @property _id The client ID, used publicly in OAuth flows
 * @property niceName Human-readable name of the client application
 * @property logo Optional URL to the client's logo (for consent screens)
 * @property scopes Set of OAuth scopes this client is allowed to request
 * @property secrets Set of client secrets with rotation support
 * @property redirectUris Allowed redirect URIs for this client (must match exactly)
 * @property postLogoutRedirectUris Allowed redirect URIs after logout (for end session endpoint)
 * @property trusted If true, skip user consent screen (for first-party/trusted applications)
 * @property firstParty If true, this is your own application (affects UI/UX display)
 * @property requirePkce If true, PKCE is required even for confidential clients (enhanced security)
 * @property allowRefreshTokens If true, client can request offline_access scope
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
    val firstParty: Boolean = false,
    val requirePkce: Boolean = false,
    val allowRefreshTokens: Boolean = true,
) : HasId<String>

/**
 * OAuth Client Secret
 *
 * Represents a client secret for OAuth authentication with rotation support.
 *
 * Clients can have multiple secrets active simultaneously to enable zero-downtime rotation.
 * Secrets are hashed (never stored in plain text) and can be disabled without deletion.
 *
 * @property createdAt When this secret was created
 * @property masked Partially masked version for display (e.g., "abc***xyz")
 * @property secretHash Hash of the actual secret (for verification)
 * @property disabledAt If set, this secret is no longer valid for authentication
 */
@Serializable
public data class OauthClientSecret(
    val createdAt: Instant,
    val masked: String,
    val secretHash: String,
    val disabledAt: Instant? = null,
)

/**
 * Consent Request
 *
 * Sent to the consent handler when user consent is required for a non-trusted client.
 *
 * @property clientId The OAuth client requesting access
 * @property clientName Human-readable name of the client application
 * @property clientLogo Optional URL to the client's logo
 * @property requestedScopes Set of OAuth scopes being requested
 * @property scopeDescriptions Human-readable descriptions of what each scope means
 * @property state Opaque value used to resume the authorization flow after consent
 */
@Serializable
public data class ConsentRequest(
    val clientId: String,
    val clientName: String,
    val clientLogo: String? = null,
    val requestedScopes: Set<String>,
    val scopeDescriptions: Map<String, String>,
    val state: String,
)

/**
 * Consent Response
 *
 * Response from user indicating whether they grant or deny consent.
 *
 * @property state The state value from the ConsentRequest
 * @property granted Whether the user granted consent
 * @property grantedScopes Optional subset of scopes granted (if user can deny specific scopes)
 */
@Serializable
public data class ConsentResponse(
    val state: String,
    val granted: Boolean,
    val grantedScopes: Set<String>? = null,
)

/**
 * Revoked Token (stored in database)
 *
 * Tracks revoked tokens to prevent their use even if still in cache.
 * Tokens can be cleaned up from this table after their expiration time.
 *
 * @property _id The token value itself (access or refresh token)
 * @property revokedAt When the token was revoked
 * @property expiresAt Original expiration time (can clean up after this)
 * @property userId User who owned the token (for auditing)
 * @property clientId Client the token was issued to (for auditing)
 */
@GenerateDataClassPaths
@Serializable
public data class RevokedToken(
    override val _id: String,
    val revokedAt: Instant,
    val expiresAt: Instant,
    val userId: String,
    val clientId: String,
) : HasId<String>

/**
 * Revocation Request (RFC 7009)
 *
 * Request to revoke an access or refresh token.
 *
 * @property token The token to revoke
 * @property token_type_hint Optional hint about token type ("access_token" or "refresh_token")
 */
@Serializable
public data class RevocationRequest(
    val token: String,
    val token_type_hint: String? = null,
)

/**
 * Introspection Request (RFC 7662)
 *
 * Request to validate a token and retrieve its metadata.
 *
 * @property token The token to introspect
 * @property token_type_hint Optional hint about token type ("access_token" or "refresh_token")
 */
@Serializable
public data class IntrospectionRequest(
    val token: String,
    val token_type_hint: String? = null,
)

/**
 * Introspection Response (RFC 7662)
 *
 * Metadata about a token.
 *
 * @property active Whether the token is currently active
 * @property scope Space-separated list of scopes
 * @property client_id Client the token was issued to
 * @property username User identifier (subject)
 * @property token_type Type of token ("access_token" or "refresh_token")
 * @property exp Expiration time (Unix timestamp)
 * @property iat Issued at time (Unix timestamp)
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

/**
 * End Session Request (OpenID Connect RP-Initiated Logout)
 *
 * Request to terminate a user's session.
 *
 * @property id_token_hint ID token previously issued (helps identify session)
 * @property post_logout_redirect_uri Where to redirect after logout
 * @property state Opaque value passed back in redirect
 */
@Serializable
public data class EndSessionRequest(
    val id_token_hint: String? = null,
    val post_logout_redirect_uri: String? = null,
    val state: String? = null,
)

/**
 * End Session Response
 *
 * Response indicating where the client should redirect after logout.
 *
 * @property redirectUri Where to redirect the user (includes state if provided)
 */
@Serializable
public data class EndSessionResponse(
    val redirectUri: String? = null
)
