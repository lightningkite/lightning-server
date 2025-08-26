package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

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

@GenerateDataClassPaths
@Serializable
public data class OauthClientSecret(
    val createdAt: Instant,
    val masked: String,
    val secretHash: String,
    val disabledAt: Instant? = null,
)

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
)

@Serializable public enum class OauthPromptType {
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

public object OauthGrantTypes {
    public const val authorizationCode:String = "authorization_code"
    public const val refreshToken:String = "refresh_token"
}