package com.lightningkite.lightningserver.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.impl.PublicClaims
import com.lightningkite.lightningserver.SettingSingleton
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import java.security.SecureRandom
import java.time.Duration
import java.util.*

/**
 * AuthSettings holds the values required to setup JWT Authentication.
 * This will be used by nearly every function in the auth package.
 * @param authDomain Used in cookies as the domain
 * @param jwtAudience Used in a JWT claim to help identify JWTs
 * @param jwtIssuer Used in a JWT claim to help identify JWTs
 * @param jwtExpirationMilliseconds The default expiration for tokens. This can be overridden for a specific token.
 * @param jwtSecret THis should be a long and complicated String. The jwtSecret should never be shared since it is what's used to sign JWTs.
 * @param oauth will define what external services you will allow to authenticate through.
 */
@Serializable
data class AuthSettings(
    val authDomain: String? = null,
    val jwtIssuer: String = GeneralServerSettings.instance.publicUrl,
    val jwtExpirationMilliseconds: Long? = Duration.ofDays(365).toMillis(),
    val jwtEmailExpirationMilliseconds: Long = Duration.ofHours(1).toMillis(),
    val jwtSecret: String = buildString {
        val rand = SecureRandom.getInstanceStrong()
        repeat(64) {
            append(
                availableCharacters[rand.nextInt(availableCharacters.length)]
            )
        }
    },
    val oauth: Map<String, OauthProviderCredentials> = mapOf()
) {
    @Serializable
    data class OauthProviderCredentials(
        val id: String,
        val secret: String
    )

    inline fun <reified T> token(subject: T, expireDuration: Long? = jwtExpirationMilliseconds): String = token(Serialization.module.serializer(), subject, expireDuration)
    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Long? = jwtExpirationMilliseconds): String = JWT.create()
        .withAudience(AuthSettings.instance.jwtIssuer)
        .withIssuer(AuthSettings.instance.jwtIssuer)
        .withIssuedAt(Date())
        .also {
            if (expireDuration != null)
                it.withExpiresAt(Date(System.currentTimeMillis() + expireDuration))
        }
        .withClaim(PublicClaims.SUBJECT, (Serialization.json.encodeToJsonElement(serializer, subject) as JsonPrimitive).content)
        .sign(Algorithm.HMAC256(AuthSettings.instance.jwtSecret))

    inline fun <reified T> verify(token: String): T = verify(Serialization.module.serializer(), token)
    fun <T> verify(serializer: KSerializer<T>, token: String): T {
        return try {
            val v = JWT
                .require(Algorithm.HMAC256(jwtSecret))
                .withIssuer(jwtIssuer)
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

    companion object : SettingSingleton<AuthSettings>() {
        const val userIdKey: String = "userId"
    }

    init {
        instance = this
    }
}

private val availableCharacters =
    "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM~!@#%^&*()_+`-=[]{};':,./<>?"