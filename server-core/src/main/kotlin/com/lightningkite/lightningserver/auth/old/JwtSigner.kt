@file:UseContextualSerialization(Duration::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.serialization.contextualSerializerIfHandled
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.encryption.SecureHasher.*
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.now
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import java.security.SecureRandom
import java.util.*
import com.lightningkite.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds


private val availableCharacters =
    "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM~!@#%^&*()_+`-=[]{};':,./<>?"

/**
 * AuthSettings holds the values required to setup JWT Authentication.
 * This will be used by nearly every function in the auth package.
 * @param expirationMilliseconds The default expiration for tokens. This can be overridden for a specific token.
 * @param secret THis should be a long and complicated String. The jwtSecret should never be shared since it is what's used to sign JWTs.
 */
@Deprecated("Move to new auth")
@Serializable
data class JwtSigner(
    val expiration: Duration = 365.days,
    val emailExpiration: Duration = 1.hours,
    val secret: String = buildString {
        val rand = SecureRandom.getInstanceStrong()
        repeat(64) {
            append(
                availableCharacters[rand.nextInt(availableCharacters.length)]
            )
        }
    },
    val issuer: String? = null,
    val audience: String? = null
) {

    @kotlinx.serialization.Transient
    val hasher = HS256(secret.toByteArray())

    /**
     * @return A JWT with the [subject], expiring in [expireDuration].
     */
    fun token(subject: String, expireDuration: Duration = expiration): String {
        return hasher.signJwt(JwtClaims(
            iss = issuer ?: generalSettings().publicUrl,
            aud = audience ?: generalSettings().publicUrl,
            exp = (now() + expireDuration).epochSeconds,
            sub = subject,
            iat = now().epochSeconds,
            scope = "*"
        ))
    }

    /**
     * Returns the subject if the token was valid.
     */
    fun verify(token: String): String {
        return try {
            val claims = hasher.verifyJwt(token, audience)
            claims!!.sub!!
        } catch (e: JwtExpiredException) {
            throw UnauthorizedException(
                message = "This authorization has expired.",
                cause = e
            )
        } catch (e: JwtException) {
            throw UnauthorizedException(
                message = "Invalid token",
                cause = e
            )
        } catch (e: Exception) {
            throw UnauthorizedException(
                message = "Invalid token",
                cause = e
            )
        }
    }


    @Deprecated(
        "Use the version with duration instead",
        ReplaceWith("token(subject, Duration.ofMillis(expireDuration))", "java.time.Duration")
    )
    inline fun <reified T> token(subject: T, expireDuration: Long): String =
        token(Serialization.module.contextualSerializerIfHandled(), subject, expireDuration.milliseconds)

    @Deprecated(
        "Use the version with duration instead",
        ReplaceWith("token(serializer, subject, Duration.ofMillis(expireDuration))", "java.time.Duration")
    )
    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Long): String =
        token(serializer, subject, expireDuration.milliseconds)

    inline fun <reified T> token(subject: T, expireDuration: Duration = expiration): String =
        token(Serialization.module.contextualSerializerIfHandled(), subject, expireDuration)

    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Duration = expiration): String {
        return hasher.signJwt(JwtClaims(
            iss = issuer ?: generalSettings().publicUrl,
            aud = audience ?: generalSettings().publicUrl,
            exp = (now() + expireDuration).epochSeconds,
            sub = Serialization.json.encodeUnwrappingString(serializer, subject),
            iat = now().epochSeconds,
            scope = "*"
        ))
    }

    inline fun <reified T> verify(token: String): T = verify(Serialization.module.contextualSerializerIfHandled(), token)
    fun <T> verify(serializer: KSerializer<T>, token: String): T {
        return try {
            hasher.verifyJwt(token, audience)!!.sub!!.let {
                Serialization.json.decodeUnwrappingString(serializer, it)
            }
        } catch (e: JwtExpiredException) {
            throw UnauthorizedException(
                message = "This authorization has expired.",
                cause = e
            )
        } catch (e: JwtException) {
            throw UnauthorizedException(
                message = "Invalid token",
                cause = e
            )
        } catch (e: Exception) {
            throw UnauthorizedException(
                message = "Invalid token",
                cause = e
            )
        }
    }

    private fun KSerializer<*>.isPrimitive(): Boolean {
        var current = this.descriptor
        while (true) {
            when (current.kind) {
                is PrimitiveKind -> return true
                SerialKind.CONTEXTUAL -> current =
                    Serialization.json.serializersModule.getContextualDescriptor(current)!!

                else -> return false
            }
        }
    }
}

