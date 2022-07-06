package com.lightningkite.lightningserver.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.impl.PublicClaims
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.time.Duration
import java.util.*


private val availableCharacters =
    "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM~!@#%^&*()_+`-=[]{};':,./<>?"

/**
 * AuthSettings holds the values required to setup JWT Authentication.
 * This will be used by nearly every function in the auth package.
 * @param expirationMilliseconds The default expiration for tokens. This can be overridden for a specific token.
 * @param secret THis should be a long and complicated String. The jwtSecret should never be shared since it is what's used to sign JWTs.
 */
@Serializable
data class JwtSigner(
    val expirationMilliseconds: Long? = Duration.ofDays(365).toMillis(),
    val emailExpirationMilliseconds: Long = Duration.ofHours(1).toMillis(),
    val secret: String = buildString {
        val rand = SecureRandom.getInstanceStrong()
        repeat(64) {
            append(
                availableCharacters[rand.nextInt(availableCharacters.length)]
            )
        }
    }
) {

    inline fun <reified T> token(subject: T, expireDuration: Long? = expirationMilliseconds): String = token(serializerOrContextual(), subject, expireDuration)
    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Long? = expirationMilliseconds): String = JWT.create()
        .withAudience(generalSettings().publicUrl)
        .withIssuer(generalSettings().publicUrl)
        .withIssuedAt(Date())
        .also {
            if (expireDuration != null)
                it.withExpiresAt(Date(System.currentTimeMillis() + expireDuration))
        }
        .withClaim(PublicClaims.SUBJECT, (Serialization.json.encodeToJsonElement(serializer, subject) as JsonPrimitive).content)
        .sign(Algorithm.HMAC256(secret))

    inline fun <reified T> verify(token: String): T = verify(serializerOrContextual(), token)
    fun <T> verify(serializer: KSerializer<T>, token: String): T {
        return try {
            val v = JWT
                .require(Algorithm.HMAC256(secret))
                .withIssuer(generalSettings().publicUrl)
                .build()
                .verify(token)
            Serialization.json.decodeFromJsonElement(serializer, JsonPrimitive(v.subject ?: v.getClaim(userIdKey).asString()))
        } catch (e: JWTVerificationException) {
            throw UnauthorizedException(
                body = "Invalid token $token: ${e.message}",
                cause = e
            )
        } catch (e: JWTDecodeException) {
            throw UnauthorizedException(
                body = "Invalid token $token: ${e.message}",
                cause = e
            )
        }
    }

    companion object {
        const val userIdKey: String = "userId"
    }
}

