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
 * A shortcut function that sets up OAuth for Google accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
class OauthGoogleEndpoints(
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
    override val niceName = "Google"
    override val codeName = "google"
    override val authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
    override val getTokenUrl = "https://oauth2.googleapis.com/token"
    override val scope = "https%3A//www.googleapis.com/auth/userinfo.email"
    override val additionalParams = "&access_type=online&include_granted_scopes=true"

    @Serializable
    private data class GoogleResponse2(
        val verified_email: Boolean,
        val email: String,
        val picture: String
    )

    override suspend fun fetchEmail(response: OauthResponse): String {
        val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
            headers {
                append("Authorization", "${response.token_type} ${response.access_token}")
            }
        }.body()
        if (!response2.verified_email) throw BadRequestException("Google has not verified the email '${response2.email}'.")
        return response2.email
    }
}
