package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents an OAuth 2.0 client application that can authenticate users via this server.
 *
 * This model stores client credentials and configuration for applications that integrate
 * with your server as an OAuth provider. Each client can have multiple secrets (for rotation)
 * and multiple redirect URIs.
 *
 * @property _id The client ID, used publicly in OAuth flows
 * @property niceName Human-readable name of the client application
 * @property logo Optional URL to the client's logo (for consent screens)
 * @property scopes Set of OAuth scopes this client is allowed to request
 * @property secrets Set of client secrets with rotation support
 * @property redirectUris Allowed redirect URIs for this client (must match exactly)
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
) : HasId<String> {

}

/**
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
@GenerateDataClassPaths
@Serializable
public data class OauthClientSecret(
    val createdAt: Instant,
    val masked: String,
    val secretHash: String,
    val disabledAt: Instant? = null,
)

/**
 * OAuth 2.0 token response containing access tokens and optional refresh/ID tokens.
 *
 * @property access_token The access token for API requests
 * @property scope Space-separated list of granted scopes
 * @property token_type Type of token (typically "Bearer")
 * @property id_token Optional OpenID Connect ID token (JWT)
 * @property refresh_token Optional token for obtaining new access tokens
 */
@Serializable
public data class OauthResponse(
    val access_token: String,
    val scope: String = "",
    val token_type: String = "Bearer",
    val id_token: String? = null,
    val refresh_token: String? = null,
)

@Serializable
public data class OauthTokenRequest(
    val code: String? = null,
    val refresh_token: String? = null,
    val client_id: String,
    val client_secret: String,
    val redirect_uri: String? = null,
    val grant_type: String = "authorization_code",
    /** PKCE (RFC 7636) code verifier proving this client started the flow. Omitted when PKCE is disabled. */
    val code_verifier: String? = null,
)

@Serializable
public data class OauthCode(
    val code: String? = null,
    val error: String? = null,
    val state: String? = null,
)

@Serializable
public data class OauthCodeRequest(
    val response_type: String,
    val scope: String,
    val redirect_uri: String,
    val client_id: String,
    val state: String = Uuid.random().toString(),
    val response_mode: OauthResponseMode = OauthResponseMode.form_post,
    val access_type: OauthAccessType? = null,
    val include_granted_scopes: Boolean? = null,
    val prompt: OauthPromptType? = null,
    val login_hint: String? = null,
    val sessionExpiration: Instant? = null,
    /** PKCE (RFC 7636) code challenge = BASE64URL-NOPAD(SHA256(code_verifier)). Omitted when PKCE is disabled. */
    val code_challenge: String? = null,
    /** PKCE transformation method; always "S256" when [code_challenge] is present. */
    val code_challenge_method: String? = null,
)

@Serializable
public enum class OauthPromptType {
    consent, select_account, none
}

@Serializable
public enum class OauthResponseMode {
    form_post, query
}

@Serializable
public enum class OauthAccessType {
    online, offline
}

/**
 * Constants for OAuth 2.0 grant type values used in token requests.
 */
public object OauthGrantTypes {
    /** Authorization code grant type for initial token exchange */
    public const val authorizationCode: String = "authorization_code"

    /** Refresh token grant type for obtaining new access tokens */
    public const val refreshToken: String = "refresh_token"
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding validation for OauthClient.redirectUris to ensure they are valid URIs
 *    and potentially enforce HTTPS in production environments.
 *
 * 2. The OauthClient._id is a String which could be any value. Consider documenting
 *    requirements/best practices (e.g., should it be a UUID? random string? specific format?)
 *
 * 3. Consider adding a method to OauthClient to check if a redirect URI is valid:
 *    fun isValidRedirectUri(uri: String): Boolean
 *
 * 4. OauthClientSecret.masked should have documented format/rules to ensure consistency
 *    (e.g., "first 3 chars + *** + last 3 chars").
 *
 * 5. Consider adding an isActive or isValid method to OauthClientSecret that checks disabledAt.
 *
 * 6. The OauthCode.error field could benefit from being an enum or sealed class representing
 *    standard OAuth error codes (invalid_request, unauthorized_client, access_denied, etc.)
 *
 * 7. Consider adding doc comments for OauthTokenRequest, OauthCode, OauthCodeRequest fields
 *    to explain the OAuth flow context.
 */