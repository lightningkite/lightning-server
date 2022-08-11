package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.client
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
class OauthGitHubEndpoints(
    path: ServerPath,
    jwtSigner: () -> JwtSigner,
    landing: HttpEndpoint,
    emailToId: suspend (String) -> String
) : OauthEndpoints(
    path = path,
    jwtSigner = jwtSigner,
    landing = landing,
    emailToId = emailToId,
) {
    override val niceName = "GitHub"
    override val codeName = "github"
    override val authUrl = "https://github.com/login/oauth/authorize"
    override val getTokenUrl = "https://github.com/login/oauth/access_token"
    override val scope = "user:email"
    override val additionalParams: String = ""

    @Serializable
    private data class GithubEmail(
        val email: String,
        val verified: Boolean,
        val primary: Boolean,
        val visibility: String? = null
    )


    override suspend fun fetchEmail(response: OauthResponse): String {
        val response2: List<GithubEmail> = client.get("https://api.github.com/user/emails") {
            headers {
                append("Authorization", "${response.token_type} ${response.access_token}")
            }
        }.body()
        val primary = response2.firstOrNull { it.primary }
            ?: response2.firstOrNull()
            ?: throw BadRequestException("No email associated with account")
        if (!primary.verified) throw BadRequestException("GitHub has not verified the email '${primary.email}'.")
        return primary.email
    }
}
