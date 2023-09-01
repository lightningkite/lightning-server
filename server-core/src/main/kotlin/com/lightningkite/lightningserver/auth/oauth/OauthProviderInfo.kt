package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.debugJsonBody
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.routes.fullUrl
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeToFormData
import com.lightningkite.lightningserver.settings.setting
import com.lightningkite.lightningserver.statusFailing
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.util.*

class OauthProviderInfo(
    val niceName: String,
    val pathName: String = niceName.lowercase().map { if(it.isLetterOrDigit()) it else '-' }.joinToString(""),
    val identifierName: String = niceName.lowercase().map { if(it.isLetterOrDigit()) it else '_' }.joinToString(""),
    val loginUrl: String,
    val tokenUrl: String,
    val mode: OauthResponseMode = OauthResponseMode.form_post,
    val settings: SettingInfo<*> = SettingInfo.standard,
    val scopeForProfile: String,
    val getProfile: suspend (OauthResponse) -> ExternalProfile
) {

    data class SettingInfo<T: Any>(
        val serializer: KSerializer<T>,
        val read: (T) -> OauthProviderCredentials
    ) {
        fun defineOptional(name: String) = setting(name, null, serializer.nullable, true) { if(it == null) null else read(it) }
        fun define(name: String, default: T) = setting(name, default, serializer, false, read)
        companion object {
            val standard = SettingInfo(OauthProviderCredentials.serializer()) { it }
            val apple = SettingInfo(OauthProviderCredentialsApple.serializer()) { it.toOauthProviderCredentials() }
        }
    }

    fun loginUrl(
        credentials: () -> OauthProviderCredentials,
        callback: HttpEndpoint,
        state: String = UUID.randomUUID().toString(),
        scope: String = scopeForProfile,
        accessType: OauthAccessType = OauthAccessType.online,
    ): String {
        val params = OauthCodeRequest(
            response_type = "code",
            scope = scope,
            state = state,
            redirect_uri = callback.path.fullUrl(),
            client_id = credentials().id,
            response_mode = mode,
            access_type = accessType,
            prompt = if(accessType == OauthAccessType.offline) OauthPromptType.consent else null,
        ).let { Serialization.properties.encodeToFormData(it) }
        return "$loginUrl?$params"
    }

    suspend fun accessToken(credentials: () -> OauthProviderCredentials, callback: HttpEndpoint, oauth: OauthCode): OauthResponse {
        oauth.error?.let {
            throw BadRequestException("Got error code '${it}' from $niceName.")
        } ?: oauth.code?.let { code ->
            return client.post(tokenUrl) {
                setBody(
                    Serialization.properties.encodeToFormData(
                        OauthTokenRequest(
                            code = code,
                            client_id = credentials().id,
                            client_secret = credentials().secret,
                            redirect_uri = callback.path.fullUrl(),
                            grant_type = OauthGrantTypes.authorizationCode,
                        )
                    )
                )
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType.Application.Json)
            }.statusFailing().body<OauthResponse>()
        }
        throw BadRequestException("Code is empty")
    }

    suspend fun accessToken(credentials: () -> OauthProviderCredentials, refreshToken: String): OauthResponse {
        return client.post(tokenUrl) {
            setBody(
                Serialization.properties.encodeToFormData(
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
        }.statusFailing().body<OauthResponse>()
    }


    companion object {
        val all = ArrayList<OauthProviderInfo>()

        val google = OauthProviderInfo(
            niceName = "Google",
            loginUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            scopeForProfile = "https://www.googleapis.com/auth/userinfo.email",
            getProfile = { response ->
                val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
                    headers {
                        append("Authorization", "${response.token_type} ${response.access_token}")
                    }
                }.body<GoogleResponse2>()
                ExternalProfile(
                    email = if (response2.verified_email) response2.email else null,
                    image = response2.picture?.takeUnless { it.isEmpty() },
                    name = response2.name?.takeUnless { it.isEmpty() },
                )
            }
        ).also { all.add(it) }

        val apple = OauthProviderInfo(
            niceName = "Apple",
            loginUrl = "https://appleid.apple.com/auth/authorize",
            tokenUrl = "https://appleid.apple.com/auth/token",
            scopeForProfile = "email",
            settings = SettingInfo.apple,
            getProfile = { response ->
                val id = (response.id_token ?: throw BadRequestException("No id_token found in response"))
                val decoded = Serialization.json.parseToJsonElement(
                    Base64.getUrlDecoder().decode(id.split('.')[1]).toString(Charsets.UTF_8)
                ) as JsonObject
                if (!decoded.get("email_verified")!!.jsonPrimitive.boolean)
                    throw BadRequestException("Apple has not verified the email address.")
                val email = decoded.get("email")!!.jsonPrimitive.content
                ExternalProfile(
                    email = email
                )
            }
        ).also { all.add(it) }

        val microsoft = OauthProviderInfo(
            niceName = "Microsoft",
            loginUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            scopeForProfile = "openid email profile",
            getProfile = { response ->
                val response2: MicrosoftAccountInfo = client.get("https://graph.microsoft.com/oidc/userinfo") {
                    headers {
                        append("Authorization", "${response.token_type} ${response.access_token}")
                    }
                }.debugJsonBody()
                ExternalProfile(
                    email = response2.email,
                    image = response2.picture,
                )
            }
        ).also { all.add(it) }

        val github = OauthProviderInfo(
            niceName = "GitHub",
            loginUrl = "https://github.com/login/oauth/authorize",
            tokenUrl = "https://github.com/login/oauth/access_token",
            scopeForProfile = "user:email read:user",
            mode = OauthResponseMode.query,
            getProfile = { response ->
                val user = run {
                    client.get("https://api.github.com/user") {
                        headers {
                            append("Authorization", "${response.token_type} ${response.access_token}")
                        }
                    }.statusFailing().body<GithubUser>()
                }
                val email = run {
                    val response2: List<GithubEmail> = client.get("https://api.github.com/user/emails") {
                        headers {
                            append("Authorization", "${response.token_type} ${response.access_token}")
                        }
                    }.statusFailing().body()
                    val primary = response2.firstOrNull { it.primary }
                        ?: response2.firstOrNull()
                        ?: return@run null
                    if (primary.verified) primary.email else null
                }
                ExternalProfile(
                    email = email,
                    username = user.login,
                    image = user.avatar_url,
                    name = user.name
                )
            }
        ).also { all.add(it) }
    }
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
    val picture: String? = null
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
    val visibility: String? = null
)