package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpEndpoint
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

/**
 * A shortcut function that sets up OAuth for Google accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
@LightningServerDsl
fun ServerPath.oauthGoogle(
    jwtSigner: ()->JwtSigner,
    landingRoute: HttpEndpoint,
    emailToId: suspend (String) -> String
) = oauth(
    jwtSigner = jwtSigner,
    landingRoute = landingRoute,
    niceName = "Google",
    codeName = "google",
    authUrl = "https://accounts.google.com/o/oauth2/v2/auth",
    getTokenUrl = "https://oauth2.googleapis.com/token",
    scope = "https%3A//www.googleapis.com/auth/userinfo.email",
    additionalParams = "&access_type=online&include_granted_scopes=true"
) {
    val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
        headers {
            append("Authorization", "${it.token_type} ${it.access_token}")
        }
    }.body()
    if (!response2.verified_email) throw BadRequestException("Google has not verified the email '${response2.email}'.")
    emailToId(response2.email)
}

@Serializable
private data class GoogleResponse2(
    val verified_email: Boolean,
    val email: String,
    val picture: String
)
