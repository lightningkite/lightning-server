package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents an OAuth 2.0 client application that authenticates users through this server.
 *
 * @property _id The client ID, which is public in OAuth flows.
 * @property niceName The name of the client application.
 * @property logo The URL of the client's logo for consent screens.
 * @property scopes The scopes the client may request.
 * @property secrets The client's secrets, of which there can be several for rotation.
 * @property redirectUris The redirect URIs the client may use, which must match exactly.
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
 * Represents a client secret, which can be disabled without deletion.
 *
 * A client can have several active secrets to rotate them without downtime.
 *
 * @property masked A partially masked form for display, such as `abc***xyz`.
 * @property secretHash The hash of the secret, which is never stored in plaintext.
 * @property disabledAt When the secret stopped being valid, or `null` if it is still valid.
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
 * Represents an OAuth 2.0 token response.
 *
 * @property scope The granted scopes, separated by spaces.
 * @property token_type The type of token, typically `Bearer`.
 * @property id_token The OpenID Connect ID token (JWT), if issued.
 * @property refresh_token The token for obtaining new access tokens, if issued.
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
    /** Holds the PKCE (RFC 7636) code verifier that proves this client started the flow, or `null` if PKCE is disabled. */
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
    /** Holds the PKCE (RFC 7636) code challenge, the unpadded base64url of the SHA-256 of the code verifier, or `null` if PKCE is disabled. */
    val code_challenge: String? = null,
    /** Holds the PKCE transformation method, which is always `S256` when [code_challenge] is present. */
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

/** Provides the grant type values used in OAuth 2.0 token requests. */
public object OauthGrantTypes {
    /** Is the grant type for the initial token exchange. */
    public const val authorizationCode: String = "authorization_code"

    /** Is the grant type for obtaining new access tokens. */
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