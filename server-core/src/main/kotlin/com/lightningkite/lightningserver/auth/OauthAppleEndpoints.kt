package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.util.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.Instant
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
    emailToId: suspend (String) -> String,
) : OauthEndpoints(
    path = path,
    codeName = "apple",
    jwtSigner = jwtSigner,
    landing = landing,
    emailToId = emailToId,
) {
    override val niceName = "Apple"
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
        return generateJwt(settings.id, settings.secret)
    }

    companion object {
        fun generateJwt(id: String, secret: String): String {
            val parts = secret.split('|')
            val teamId = parts[0]
            val keyId = parts[1]
            val keyString = parts[2]
            return buildString {
                val withDefaults = Json { encodeDefaults = true; explicitNulls = false }
                append(Base64.getUrlEncoder().withoutPadding().encodeToString(withDefaults.encodeToString(buildJsonObject {
//                    put("typ", "JWT")
                    put("kid", keyId)
                    put("alg", "ES256")
                }).toByteArray()))
                append('.')
                val issuedAt = Instant.now()
                append(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        withDefaults.encodeToString(
                            buildJsonObject {
                                put("iss", teamId)
                                put("iat", issuedAt.toEpochMilli().div(1000))
                                put("exp", issuedAt.plus(Duration.ofDays(5)).toEpochMilli().div(1000))
                                put("aud", "https://appleid.apple.com")
                                put("sub", id)
                            }
                        ).toByteArray()
                    )
                )
                val soFar = this.toString()
                append('.')
                append(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(SecureHasher.ECDSA256(keyString).sign(soFar.toByteArray()))
                )
            }
        }
    }
}