package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.setting
import io.ktor.util.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.time.Duration
import java.util.*

/**
 * A shortcut function that sets up OAuth for Apple accounts specifically.
 *
 * @param defaultLanding The final page to send the user after authentication.
 * @param emailToId A lambda that returns the users ID given an email.
 */
@LightningServerDsl
fun ServerPath.oauthApple(
    jwtSigner: ()->JwtSigner,
    landingRoute: HttpEndpoint,
    emailToId: suspend (String) -> String
) {
    Security.addProvider(BouncyCastleProvider())
    val settings = setting<OauthProviderCredentials?>("oauth-apple", null)
    return oauth(
        jwtSigner = jwtSigner,
        landingRoute = landingRoute,
        niceName = "Apple",
        codeName = "apple",
        authUrl = "https://appleid.apple.com/auth/authorize",
        getTokenUrl = "https://appleid.apple.com/auth/token",
        scope = "email",
        additionalParams = "&response_mode=form_post",
        secretTransform = {
            val settings = settings() ?: throw NotFoundException("Oauth is not configured for Apple.")
            val teamId = settings.secret.substringBefore("|")
            val keyString = settings.secret.substringAfter("|")
            Serialization.json.encodeJwt(
                hasher = SecureHasher.ECDSA256(keyString),
                serializer = String.serializer(),
                subject = settings.id,
                expire = Duration.ofDays(1),
                issuer = teamId,
                audience = "https://appleid.apple.com"
            )
        }
    ) {
        val id = (it.id_token ?: throw BadRequestException("No id_token found in response"))
        val decoded = Serialization.json.parseToJsonElement(Base64.getUrlDecoder().decode(id.split('.')[1]).toString(Charsets.UTF_8)) as JsonObject
        if (!decoded.get("email_verified")!!.jsonPrimitive.boolean)
            throw BadRequestException("Apple has not verified the email address.")
        emailToId(decoded.get("email")!!.jsonPrimitive.content)
    }
}
