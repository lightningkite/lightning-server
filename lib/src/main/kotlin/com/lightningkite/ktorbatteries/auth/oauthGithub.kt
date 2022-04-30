package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

fun Route.oauthGithub(
    defaultLanding: String = GeneralServerSettings.instance.publicUrl,
    emailToId: suspend (String) -> String
) = oauth(
    niceName = "GitHub",
    codeName = "github",
    authUrl = "https://github.com/login/oauth/authorize",
    getTokenUrl = "https://github.com/login/oauth/access_token",
    scope = "user:email",
    defaultLanding = defaultLanding
) {
    val response2: List<GithubEmail> = client.get("https://api.github.com/user/emails") {
        headers {
            append("Authorization", "${it.token_type} ${it.access_token}")
        }
    }.body()
    val primary = response2.firstOrNull { it.primary }
        ?: response2.firstOrNull()
        ?: throw BadRequestException("No email associated with account")
    if (!primary.verified) throw BadRequestException("GitHub has not verified the email '${primary.email}'.")
    emailToId(primary.email)
}


@Serializable
private data class GithubEmail(
    val email: String,
    val verified: Boolean,
    val primary: Boolean,
    val visibility: String? = null
)


