package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpEndpoint
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import java.util.*

/**
 * A shortcut function that sets up OAuth for GitHub accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
@LightningServerDsl
fun ServerPath.oauthGithub(
    jwtSigner: ()->JwtSigner,
    landingRoute: HttpEndpoint,
    emailToId: suspend (String) -> String
) = oauth(
    jwtSigner = jwtSigner,
    landingRoute = landingRoute,
    niceName = "GitHub",
    codeName = "github",
    authUrl = "https://github.com/login/oauth/authorize",
    getTokenUrl = "https://github.com/login/oauth/access_token",
    scope = "user:email"
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


