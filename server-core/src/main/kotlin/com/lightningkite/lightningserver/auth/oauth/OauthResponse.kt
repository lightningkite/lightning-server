package com.lightningkite.lightningserver.auth.oauth

import com.lightningkite.uuid
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class OauthResponse(
    val access_token: String,
    val scope: String = "",
    val token_type: String = "Bearer",
    val id_token: String? = null,
    val refresh_token: String? = null,
)

@Serializable
data class OauthTokenRequest(
    val code: String? = null,
    val refresh_token: String? = null,
    val client_id: String,
    val client_secret: String,
    val redirect_uri: String? = null,
    val grant_type: String = "authorization_code",
)

@Serializable
data class OauthCode(
    val code: String? = null,
    val error: String? = null,
    val state: String? = null,
)

@Serializable
data class OauthCodeRequest(
    val response_type: String,
    val scope: String,
    val redirect_uri: String,
    val client_id: String,
    val state: String = uuid().toString(),
    val response_mode: OauthResponseMode = OauthResponseMode.form_post,
    val access_type: OauthAccessType? = null,
    val include_granted_scopes: Boolean? = null,
    val prompt: OauthPromptType? = null,
    val login_hint: String? = null,
)

@Serializable enum class OauthPromptType {
    consent, select_account, none
}

@Serializable
enum class OauthResponseMode {
    form_post, query
}
@Serializable
enum class OauthAccessType {
    online, offline
}

object OauthGrantTypes {
    const val authorizationCode = "authorization_code"
    const val refreshToken = "refresh_token"
}