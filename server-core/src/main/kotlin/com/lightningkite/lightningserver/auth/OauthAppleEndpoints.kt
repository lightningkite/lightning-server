package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.util.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.*

/**
 * A shortcut function that sets up OAuth for Apple accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
class OauthAppleEndpoints(
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
    override val niceName = "Apple"
    override val codeName = "apple"
    override val authUrl = "https://appleid.apple.com/auth/authorize"
    override val getTokenUrl = "https://appleid.apple.com/auth/token"
    override val scope = "email"
    override val additionalParams = "&response_mode=form_post"
    override suspend fun fetchEmail(response: OauthResponse): String {
        val id = (response.id_token ?: throw BadRequestException("No id_token found in response"))
        val decoded = Serialization.json.parseToJsonElement(
            Base64.getUrlDecoder().decode(id.split('.')[1]).toString(Charsets.UTF_8)
        ) as JsonObject
        if (!decoded.get("email_verified")!!.jsonPrimitive.boolean)
            throw BadRequestException("Apple has not verified the email address.")
        return decoded.get("email")!!.jsonPrimitive.content
    }

    override fun secretTransform(secret: String): String {
        val settings = settings() ?: throw NotFoundException("Oauth is not configured for Apple.")
        val teamId = settings.secret.substringBefore("|")
        val keyString = settings.secret.substringAfter("|")
        return Serialization.json.encodeJwt(
            hasher = SecureHasher.ECDSA256(keyString),
            serializer = String.serializer(),
            subject = settings.id,
            expire = Duration.ofDays(1),
            issuer = teamId,
            audience = "https://appleid.apple.com"
        )
    }
}
