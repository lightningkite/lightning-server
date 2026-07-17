package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.FormDataFormat
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo.Companion.apple
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo.Companion.github
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo.Companion.google
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthProviderInfo.Companion.microsoft
import com.lightningkite.services.http.client
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.uuid.Uuid

/**
 * Configuration for an OAuth 2.0 identity provider.
 *
 * This class encapsulates all the information needed to integrate with an OAuth provider
 * like Google, Apple, Microsoft, or GitHub. It handles the OAuth authorization code flow,
 * token exchange, and profile retrieval.
 *
 * **Built-in Providers:**
 * - [google] - Google OAuth with email verification
 * - [apple] - Apple Sign In with email verification
 * - [microsoft] - Microsoft/Azure AD with OpenID Connect
 * - [github] - GitHub OAuth with email verification
 *
 * **Custom Providers:**
 * You can create custom provider configurations for any OAuth 2.0 compliant service:
 * ```kotlin
 * val customProvider = OauthProviderInfo(
 *     niceName = "MyProvider",
 *     loginUrl = "https://provider.com/oauth/authorize",
 *     tokenUrl = "https://provider.com/oauth/token",
 *     scopeForProfile = "email profile",
 *     getProfile = { response ->
 *         // Fetch user profile using access token
 *         ExternalProfile(email = "user@example.com")
 *     }
 * )
 * ```
 *
 * @property niceName Human-readable name (e.g., "Google", "Apple")
 * @property pathName URL-safe name derived from niceName (e.g., "google", "my-provider")
 * @property identifierName Code-safe identifier derived from niceName (e.g., "google", "my_provider")
 * @property loginUrl OAuth authorization endpoint URL
 * @property tokenUrl OAuth token exchange endpoint URL
 * @property mode How the OAuth provider sends the authorization code (form_post or query)
 * @property settings Configuration for credentials serialization (standard or provider-specific)
 * @property scopeForProfile OAuth scopes required to retrieve user profile information
 * @property supportsPkce Whether this provider accepts PKCE (RFC 7636) parameters on the authorization
 *   and token requests. Defaults to `true`; all major providers support (and recommend) PKCE. Set to
 *   `false` only for a non-compliant provider that rejects unknown `code_challenge`/`code_verifier` params.
 * @property getProfile Async function that retrieves user profile from the provider
 */
public class OauthProviderInfo(
    public val niceName: String,
    public val pathName: String = niceName.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString(""),
    public val identifierName: String = niceName.lowercase().map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString(""),
    public val loginUrl: String,
    public val tokenUrl: String,
    public val mode: OauthResponseMode = OauthResponseMode.form_post,
    public val settings: SettingInfo<*> = SettingInfo.standard,
    public val scopeForProfile: String,
    public val supportsPkce: Boolean = true,
    public val getProfile: suspend context(ServerRuntime) (OauthResponse, OauthProviderCredentials?) -> ExternalProfile,
) {
    public data class SettingInfo<T : Any>(
        val serializer: KSerializer<T>,
        val read: context(ServerRuntime) (T) -> OauthProviderCredentials,
    ) {
        public companion object {
            public val standard: SettingInfo<OauthProviderCredentials> =
                SettingInfo(OauthProviderCredentials.serializer()) { it }
            public val apple: SettingInfo<OauthProviderCredentialsApple> =
                SettingInfo(OauthProviderCredentialsApple.serializer()) { it.toOauthProviderCredentials() }
        }
    }

    context(runtime: ServerRuntime)
    public fun loginUrl(
        credentials: Runtime<OauthProviderCredentials>,
        redirectUri: String,
        state: String = Uuid.random().toString(),
        scope: String = scopeForProfile,
        accessType: OauthAccessType = OauthAccessType.online,
        prompt: OauthPromptType? = if (accessType == OauthAccessType.offline) OauthPromptType.consent else null,
        loginHint: String? = null,
        codeChallenge: String? = null,
    ): String {
        val params = OauthCodeRequest(
            response_type = "code",
            scope = scope,
            state = state,
            redirect_uri = redirectUri,
            client_id = credentials().id,
            response_mode = mode,
            access_type = accessType,
            prompt = prompt,
            login_hint = loginHint,
            code_challenge = codeChallenge,
            code_challenge_method = codeChallenge?.let { "S256" },
        ).let { FormDataFormat(EmptySerializersModule()).encodeToString(OauthCodeRequest.serializer(), it) }
        return "$loginUrl?$params"
    }

    context(runtime: ServerRuntime)
    public suspend fun accessToken(
        credentials: Runtime<OauthProviderCredentials>,
        redirectUri: String,
        oauth: OauthCode,
        codeVerifier: String? = null,
    ): OauthResponse {
        oauth.error?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: oauth.code?.let { code ->
            return client.post(tokenUrl) {
                setBody(
                    FormDataFormat(EmptySerializersModule()).encodeToString(
                        OauthTokenRequest.serializer(),
                        OauthTokenRequest(
                            code = code,
                            client_id = credentials().id,
                            client_secret = credentials().secret,
                            redirect_uri = redirectUri,
                            grant_type = OauthGrantTypes.authorizationCode,
                            code_verifier = codeVerifier,
                        )
                    )
                )
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType.Application.Json)
            }.internalBody<OauthResponse>()
        }
        throw BadRequestException("Code is empty")
    }

    context(runtime: ServerRuntime)
    public suspend fun accessToken(credentials: Runtime<OauthProviderCredentials>, refreshToken: String): OauthResponse {
        return client.post(tokenUrl) {
            setBody(
                FormDataFormat(EmptySerializersModule()).encodeToString(
                    OauthTokenRequest.serializer(),
                    OauthTokenRequest(
                        refresh_token = refreshToken,
                        client_id = credentials().id,
                        client_secret = credentials().secret,
                        grant_type = OauthGrantTypes.refreshToken,
                    )
                )
            )
            contentType(ContentType.Application.FormUrlEncoded)
            accept(ContentType.Application.Json)
        }.internalBody<OauthResponse>()
    }


    public companion object {
        /**
         * Registry of all available OAuth providers.
         * Built-in providers are automatically added to this list.
         */
        public val all: ArrayList<OauthProviderInfo> = ArrayList<OauthProviderInfo>()

        public val google: OauthProviderInfo = OauthProviderInfo(
            niceName = "Google",
            loginUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            scopeForProfile = "https://www.googleapis.com/auth/userinfo.email",
            getProfile = { response, _ ->
                val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
                    headers {
                        append("Authorization", "${response.token_type} ${response.access_token}")
                    }
                }.internalBody<GoogleResponse2>()
                ExternalProfile(
                    email = if (response2.verified_email) response2.email else null,
                    image = response2.picture?.takeUnless { it.isEmpty() },
                    name = response2.name?.takeUnless { it.isEmpty() },
                )
            }
        ).also { all.add(it) }

        public val apple: OauthProviderInfo = OauthProviderInfo(
            niceName = "Apple",
            loginUrl = "https://appleid.apple.com/auth/authorize",
            tokenUrl = "https://appleid.apple.com/auth/token",
            scopeForProfile = "email",
            settings = SettingInfo.apple,
            getProfile = { response, credentials ->
                val idToken = response.id_token ?: throw BadRequestException("No id_token found in response")
                val clientId = credentials?.id
                    ?: throw BadRequestException("Client credentials required for Apple ID token verification")

                // Verify the JWT signature and extract claims securely using Apple's public keys
                val claims = AppleJwtVerifier.verifyAppleIdToken(
                    idToken = idToken,
                    expectedAudience = clientId
                )

                // Apple tokens include email_verified claim - check it
                val claimsJson = serverRuntime.externalSerialization.json.parseToJsonElement(
                    serverRuntime.externalSerialization.json.encodeToString(claims)
                ).jsonObject

                val sub = claimsJson.get("sub")?.jsonPrimitive?.content
                    ?: throw BadRequestException("Subject id must be present")

                val emailVerified = claimsJson.get("email_verified")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: claimsJson.get("email_verified")?.jsonPrimitive?.boolean
                    ?: false

                // Email will be null on 2nd+ logins
                val email = if (emailVerified) claimsJson.get("email")?.jsonPrimitive?.content else null

                ExternalProfile(
                    id = sub,
                    email = email
                )
            }
        ).also { all.add(it) }

        public val microsoft: OauthProviderInfo = OauthProviderInfo(
            niceName = "Microsoft",
            loginUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            scopeForProfile = "openid email profile",
            getProfile = { response, _ ->
                val response2: MicrosoftAccountInfo = client.get("https://graph.microsoft.com/oidc/userinfo") {
                    headers {
                        append("Authorization", "${response.token_type} ${response.access_token}")
                    }
                }.body()
                ExternalProfile(
                    email = response2.email,
                    image = response2.picture,
                )
            }
        ).also { all.add(it) }

        public val github: OauthProviderInfo = OauthProviderInfo(
            niceName = "GitHub",
            loginUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            scopeForProfile = "user:email read:user",
            mode = OauthResponseMode.query,
            getProfile = { response, _ ->
                val user = run {
                    client.get("https://api.github.com/user") {
                        headers {
                            append("Authorization", "${response.token_type} ${response.access_token}")
                        }
                    }.internalBody<GithubUser>()
                }
                val email = run {
                    val response2: List<GithubEmail> = client.get("https://api.github.com/user/emails") {
                        headers {
                            append("Authorization", "${response.token_type} ${response.access_token}")
                        }
                    }.body()
                    val primary = response2.firstOrNull { it.primary }
                        ?: response2.firstOrNull()
                        ?: return@run null
                    if (primary.verified) primary.email else null
                }
                ExternalProfile(
                    id = user.id.toString(),
                    email = email,
                    username = user.login,
                    image = user.avatar_url,
                    name = user.name
                )
            }
        ).also { all.add(it) }
    }
}

context(runtime: ServerRuntime)
private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.internalBody(): T = bodyAsText().let {
    runtime.externalSerialization.json.decodeFromString(it)
}


@Serializable
private data class GoogleResponse2(
    val verified_email: Boolean,
    val email: String,
    val picture: String? = null,
    val name: String? = null,
)

@Serializable
private data class MicrosoftAccountInfo(
    val email: String? = null,
    val picture: String? = null,
)

@Serializable
private data class GithubUser(
    val login: String? = null,
    val id: Long? = null,
    val url: String? = null,
    val email: String? = null,
    val avatar_url: String? = null,
    val name: String? = null,
)

@Serializable
private data class GithubEmail(
    val email: String,
    val verified: Boolean,
    val primary: Boolean,
    val visibility: String? = null,
)

/*
 * TODO: API Recommendations
 *
 * 1. The pathName and identifierName transformations replace non-alphanumeric characters
 *    with '-' and '_', but consecutive non-alphanumeric characters become consecutive
 *    delimiters (e.g., "My  Provider" -> "my--provider"). Consider collapsing consecutive
 *    delimiters into a single one.
 *
 * 2. Consider making the 'all' list immutable (List instead of ArrayList) to prevent
 *    accidental modification. Providers should be registered during initialization only.
 *
 * 3. The Apple provider decodes the JWT id_token manually (line 148). Consider using a
 *    JWT library for proper validation (signature, expiration, issuer, audience).
 *    Current implementation doesn't verify the JWT signature, which is a potential security risk.
 *
 * 4. Error handling for profile retrieval could be more specific. Consider wrapping
 *    provider-specific exceptions with context about which provider failed.
 *
 * 5. The Google provider checks `verified_email` but other providers have different
 *    verification approaches. Consider documenting the email verification guarantees
 *    for each provider.
 *
 * 6. Consider adding a 'validate()' method to check if required configuration is present
 *    and URLs are well-formed.
 *
 * 7. The GitHub provider makes two API calls (user + emails). Consider if the user endpoint's
 *    email field could be used when it's available and verified to save an API call.
 *
 * 8. HTTP client configuration (timeouts, retries) is not exposed. Consider making it
 *    configurable for production reliability.
 *
 * 9. The accessToken methods for refresh tokens (line 99) don't handle the case where
 *    the refresh token is expired or revoked. Consider more specific error handling.
 */