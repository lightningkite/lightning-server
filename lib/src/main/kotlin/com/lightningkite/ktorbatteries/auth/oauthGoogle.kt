package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.util.*

@KtorDsl
fun Route.oauthGoogle(
    defaultLanding: String = GeneralServerSettings.instance.publicUrl,
    emailToId: suspend (String) -> String
) = oauth(
    niceName = "Google",
    codeName = "google",
    authUrl = "https://accounts.google.com/o/oauth2/v2/auth",
    getTokenUrl = "https://oauth2.googleapis.com/token",
    scope = "https%3A//www.googleapis.com/auth/userinfo.email",
    additionalParams = "&access_type=online&include_granted_scopes=true",
    defaultLanding = defaultLanding
) {
    val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
        headers {
            append("Authorization", "${it.token_type} ${it.access_token}")
        }
    }.body()
    if(!response2.verified_email) throw BadRequestException("Google has not verified the email '${response2.email}'.")
    emailToId(response2.email)
}

@Serializable
private data class GoogleResponse2(
    val verified_email: Boolean,
    val email: String,
    val picture: String
)
