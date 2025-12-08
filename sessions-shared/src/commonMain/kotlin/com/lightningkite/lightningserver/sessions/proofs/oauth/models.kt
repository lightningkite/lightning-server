package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents an OAuth 2.0 client application that is authorized to request access tokens
 * on behalf of users through the authorization code flow.
 *
 * OAuth clients are registered applications that have been granted permission to interact
 * with the OAuth server. Each client is identified by a unique client ID and authenticates
 * using client secrets.
 *
 * Security considerations:
 * - Client IDs are public and can be exposed in client-side code
 * - Client secrets must be kept confidential and stored securely
 * - Redirect URIs should be validated strictly to prevent authorization code interception attacks
 * - Scopes should follow the principle of least privilege
 *
 * @property _id The unique client identifier (client_id in OAuth 2.0 terminology).
 *               This is public and used in authorization requests.
 * @property niceName Human-readable display name for the client application,
 *                    shown to users during authorization consent.
 * @property logo Optional URL to the client application's logo image,
 *                displayed during the authorization flow for user recognition.
 * @property scopes Set of permission scopes this client is allowed to request.
 *                  Limits what access the client can request from users.
 * @property secrets Set of client secrets used for authentication during token exchange.
 *                   Multiple secrets allow for secret rotation without service interruption.
 * @property redirectUris Whitelist of valid redirect URIs where authorization codes can be sent.
 *                        Critical for preventing authorization code interception attacks.
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
 * Represents a client secret credential used for OAuth client authentication during token exchange.
 *
 * Client secrets are confidential credentials that prove the client's identity when exchanging
 * authorization codes for access tokens. This data class supports secret rotation by allowing
 * multiple secrets per client and tracking when secrets are created and disabled.
 *
 * Security considerations:
 * - The actual secret value is never stored; only a cryptographic hash is persisted
 * - Secrets should be transmitted only over secure channels (HTTPS)
 * - Disabled secrets should not be accepted for authentication
 * - The masked version provides a way to identify secrets without exposing their values
 *
 * @property createdAt Timestamp when this secret was generated,
 *                     used for auditing and determining secret age.
 * @property masked A partially obscured version of the secret for identification purposes
 *                  (e.g., "abc***xyz"), allowing administrators to identify which secret
 *                  is being used without exposing the full value.
 * @property secretHash Cryptographic hash of the actual secret value,
 *                      used for verification during client authentication.
 *                      The plain-text secret is never stored.
 * @property disabledAt Optional timestamp when this secret was disabled.
 *                      If set, this secret should no longer be accepted for authentication.
 *                      Allows for graceful secret rotation.
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
 * Represents the successful token response returned by the OAuth token endpoint.
 *
 * This is the response sent back to the client application after successfully exchanging
 * an authorization code or refresh token for access credentials. It follows the OAuth 2.0
 * token response specification (RFC 6749 Section 5.1).
 *
 * Security considerations:
 * - Access tokens should be short-lived to limit exposure if compromised
 * - Refresh tokens should only be issued to confidential clients
 * - All tokens should be transmitted only over secure channels (HTTPS)
 * - ID tokens contain user identity information and should be validated by clients
 *
 * @property access_token The access token issued by the authorization server.
 *                        This token is used to authenticate API requests on behalf of the user.
 *                        Required in all successful responses.
 * @property scope Space-delimited list of scopes granted by this token.
 *                 May differ from requested scopes if the user granted only a subset.
 * @property token_type The type of token issued, typically "Bearer" per RFC 6750.
 *                      Indicates how the token should be used in API requests
 *                      (e.g., "Authorization: Bearer <token>").
 * @property id_token Optional OpenID Connect ID token (JWT) containing user identity information.
 *                    Only included when the "openid" scope was granted.
 *                    Clients should validate the signature and claims.
 * @property refresh_token Optional refresh token that can be used to obtain new access tokens
 *                         without user interaction. Only issued for offline access or when
 *                         explicitly requested. Should be stored securely by the client.
 */
@Serializable
public data class OauthResponse(
    val access_token: String,
    val scope: String = "",
    val token_type: String = "Bearer",
    val id_token: String? = null,
    val refresh_token: String? = null,
)

/**
 * Represents a request to the OAuth token endpoint to exchange credentials for access tokens.
 *
 * This request is made by the client application to exchange either an authorization code
 * (authorization code flow) or a refresh token (refresh token flow) for access credentials.
 * The client must authenticate using its client ID and secret.
 *
 * Security considerations:
 * - This request must be made server-to-server, never from client-side code
 * - Client secrets must be transmitted only over HTTPS
 * - Authorization codes should be single-use and short-lived
 * - The redirect_uri must match exactly what was used in the authorization request
 *
 * @property code Authorization code received from the authorization endpoint.
 *                Required when grant_type is "authorization_code".
 *                This code is exchanged for access tokens and should be single-use.
 * @property refresh_token Refresh token for obtaining new access tokens.
 *                         Required when grant_type is "refresh_token".
 *                         Used to get new access tokens without user interaction.
 * @property client_id The client identifier issued during client registration.
 *                     Identifies which application is requesting tokens.
 * @property client_secret The client secret for authentication.
 *                         Proves the client's identity to the authorization server.
 *                         Must be kept confidential.
 * @property redirect_uri The redirect URI used in the authorization request.
 *                        Required for authorization code grants.
 *                        Must match exactly to prevent authorization code interception attacks.
 * @property grant_type The OAuth grant type being used.
 *                      Either "authorization_code" for initial token exchange or
 *                      "refresh_token" for token refresh. Defaults to "authorization_code".
 */
@Serializable
public data class OauthTokenRequest(
    val code: String? = null,
    val refresh_token: String? = null,
    val client_id: String,
    val client_secret: String,
    val redirect_uri: String? = null,
    val grant_type: String = "authorization_code",
)

/**
 * Represents the response from the OAuth authorization endpoint, containing either
 * an authorization code or an error.
 *
 * After the user grants or denies authorization, the authorization server redirects
 * back to the client's redirect URI with this response. The client application uses
 * this to determine whether authorization succeeded and to retrieve the authorization code.
 *
 * Security considerations:
 * - The state parameter should be validated to prevent CSRF attacks
 * - Authorization codes should be exchanged immediately and are single-use
 * - Errors should be handled carefully to avoid information disclosure
 *
 * @property code The authorization code generated by the authorization server.
 *                Present when authorization was successful.
 *                This code is exchanged for access tokens at the token endpoint.
 *                Should be short-lived and single-use.
 * @property error Error code if authorization failed (e.g., "access_denied", "invalid_request").
 *                 Present when authorization was unsuccessful.
 *                 Mutually exclusive with code.
 * @property state The state parameter originally sent in the authorization request.
 *                 Used for CSRF protection - client must validate this matches
 *                 the state value it sent in the authorization request.
 */
@Serializable
public data class OauthCode(
    val code: String? = null,
    val error: String? = null,
    val state: String? = null,
)

/**
 * Represents a request to the OAuth authorization endpoint to initiate the authorization code flow.
 *
 * This request is initiated by the client application to request user authorization.
 * The user is redirected to the authorization endpoint with these parameters, where they
 * are prompted to grant or deny the requested permissions.
 *
 * Security considerations:
 * - The state parameter is critical for CSRF protection and should be unique per request
 * - The redirect_uri must be pre-registered and validated by the authorization server
 * - Scope requests should follow the principle of least privilege
 * - The authorization request can be manipulated by users, so validate all parameters
 *
 * @property response_type The type of response desired, typically "code" for authorization code flow.
 *                         Determines what the authorization endpoint returns.
 * @property scope Space-delimited list of permission scopes being requested
 *                 (e.g., "openid profile email"). The user will be asked to consent to these.
 * @property redirect_uri The URI where the authorization server will redirect after user consent.
 *                        Must match a pre-registered redirect URI for this client.
 *                        Critical for preventing authorization code interception.
 * @property client_id The client identifier issued during client registration.
 *                     Identifies which application is requesting authorization.
 * @property state A random value for CSRF protection and maintaining state between request and callback.
 *                 Defaults to a random UUID. The client must validate this in the response.
 * @property response_mode How the authorization response should be returned.
 *                         Either "form_post" (POST to redirect_uri) or "query" (URL parameters).
 *                         Defaults to form_post for better security.
 * @property access_type Whether the application needs offline access.
 *                       "offline" requests a refresh token, "online" does not.
 *                       Optional, provider-specific extension.
 * @property include_granted_scopes Whether to include previously granted scopes in the access token.
 *                                  Optional, provider-specific extension (Google).
 * @property prompt Controls the authorization server's prompting behavior.
 *                  Can force consent screen, account selection, or silent authentication.
 *                  Optional, OpenID Connect extension.
 * @property login_hint Hint about the user's identity (e.g., email address) to pre-fill the login form.
 *                      Optional, OpenID Connect extension.
 * @property sessionExpiration Optional timestamp when the resulting session should expire.
 *                             Implementation-specific extension for session duration control.
 */
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
)

/**
 * Controls the authorization server's user interaction prompting behavior during the authorization flow.
 *
 * This OpenID Connect extension parameter allows clients to control whether and how the
 * authorization server prompts the user for reauthentication or consent.
 *
 * @property consent Forces the authorization server to display the consent screen,
 *                   even if the user has previously granted consent.
 *                   Useful when applications want to ensure explicit user consent.
 * @property select_account Forces the authorization server to prompt the user to select an account,
 *                          even if there is only one account or a session exists.
 *                          Useful for multi-account scenarios.
 * @property none Requests that the authorization server not display any authentication or
 *                consent screens. If the user is not already authenticated or consent is needed,
 *                an error is returned instead. Used for silent authentication checks.
 */
@Serializable public enum class OauthPromptType {
    consent, select_account, none
}

/**
 * Specifies how the authorization server should return the authorization response to the client.
 *
 * This parameter controls the HTTP mechanism used to deliver the authorization code or error
 * back to the client's redirect URI after the user grants or denies authorization.
 *
 * Security considerations:
 * - form_post is generally more secure as it doesn't expose the response in browser history or referer headers
 * - query mode exposes parameters in URL, which may be logged by proxies and browsers
 *
 * @property form_post The authorization response is returned as an HTTP POST to the redirect URI.
 *                     More secure as parameters are sent in the request body, not visible in URLs.
 *                     Recommended for authorization codes containing sensitive information.
 * @property query The authorization response is returned as query parameters appended to the redirect URI.
 *                 Traditional OAuth 2.0 method, simpler but less secure as parameters appear in URLs.
 */
@Serializable
public enum class OauthResponseMode {
    form_post, query
}
/**
 * Indicates whether the application needs offline access to the user's resources.
 *
 * This parameter, primarily used by Google's OAuth implementation, controls whether
 * the authorization server should issue a refresh token along with the access token.
 *
 * Security considerations:
 * - Refresh tokens provide long-lived access and should only be requested when necessary
 * - Applications with refresh tokens can access user resources even when the user is offline
 * - Refresh tokens should be stored securely and treated as highly sensitive credentials
 *
 * @property online Requests access only while the user is present.
 *                  No refresh token is issued, only an access token.
 *                  Appropriate for web applications where the user is actively using the application.
 * @property offline Requests a refresh token for offline access.
 *                   Allows the application to obtain new access tokens without user interaction.
 *                   Appropriate for applications that need to access resources in the background
 *                   or when the user is not actively using the application.
 */
@Serializable
public enum class OauthAccessType {
    online, offline
}

/**
 * Constants for OAuth 2.0 grant type values as defined in RFC 6749.
 *
 * Grant types specify the method used to obtain an access token from the authorization server.
 * These constants provide type-safe access to the standard grant type strings used in
 * token requests to prevent typos and ensure compliance with the OAuth 2.0 specification.
 */
public object OauthGrantTypes {
    /**
     * The authorization code grant type.
     *
     * Used when exchanging an authorization code for access tokens. This is the most common
     * OAuth 2.0 flow for server-side applications. The client receives an authorization code
     * from the authorization endpoint and exchanges it for an access token at the token endpoint.
     *
     * Value: "authorization_code"
     */
    public const val authorizationCode:String = "authorization_code"

    /**
     * The refresh token grant type.
     *
     * Used when exchanging a refresh token for a new access token. This allows applications
     * to obtain new access tokens without requiring user interaction, as long as the refresh
     * token remains valid.
     *
     * Value: "refresh_token"
     */
    public const val refreshToken:String = "refresh_token"
}