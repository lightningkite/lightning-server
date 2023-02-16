package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.routes.fullUrl
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class OauthResponse(
    val access_token: String,
    val scope: String = "",
    val token_type: String = "Bearer",
    val id_token: String? = null,
)

@Serializable
data class OauthTokenRequest(
    val code: String,
    val client_id: String,
    val client_secret: String,
    val redirect_uri: String,
    val grant_type: String = "authorization_code",
)

@Serializable
data class OauthCode(
    val code: String? = null,
    val error: String? = null
)

@Serializable
data class OauthCodeRequest(
    val response_type: String,
    val scope: String,
    val redirect_uri: String,
    val client_id: String,
    val state: String = UUID.randomUUID().toString(),
    val response_mode: String = "form_post"
)