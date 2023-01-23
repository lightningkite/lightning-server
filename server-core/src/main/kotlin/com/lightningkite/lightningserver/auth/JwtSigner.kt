@file:UseContextualSerialization(Duration::class)
package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization

import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
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
    val expiration: Duration = Duration.ofDays(365),
    val emailExpiration: Duration = Duration.ofHours(1),
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
    val hasher = SecureHasher.HS256(secret.toByteArray())

    /**
     * @return A JWT with the [subject], expiring in [expireDuration].
     */
    fun token(subject: String, expireDuration: Duration = expiration): String =
        Serialization.json.encodeJwt(hasher, subject, expireDuration, issuer ?: generalSettings().publicUrl, audience ?: generalSettings().publicUrl)

    /**
     * Returns the subject if the token was valid.
     */
    fun verify(token: String): String {
        return try {
            Serialization.json.decodeJwt(hasher, token, audience ?: generalSettings().publicUrl)
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
        } catch (e: Exception){
            throw UnauthorizedException(
                message = "Invalid token",
                cause = e
            )
        }
    }


    @Deprecated("Use the version with duration instead", ReplaceWith("token(subject, Duration.ofMillis(expireDuration))", "java.time.Duration"))
    inline fun <reified T> token(subject: T, expireDuration: Long): String =
        token(Serialization.module.serializer(), subject, Duration.ofMillis(expireDuration))
    @Deprecated("Use the version with duration instead", ReplaceWith("token(serializer, subject, Duration.ofMillis(expireDuration))", "java.time.Duration"))
    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Long): String =
        token(serializer, subject, Duration.ofMillis(expireDuration))

    inline fun <reified T> token(subject: T, expireDuration: Duration = expiration): String =
        token(Serialization.module.serializer(), subject, expireDuration)
    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Duration = expiration): String =
        Serialization.json.encodeJwt(hasher, serializer, subject, expireDuration, issuer ?: generalSettings().publicUrl, audience ?: generalSettings().publicUrl)

    inline fun <reified T> verify(token: String): T = verify(Serialization.module.serializer(), token)
    fun <T> verify(serializer: KSerializer<T>, token: String): T {
        return try {
            Serialization.json.decodeJwt(hasher, serializer, token, audience ?: generalSettings().publicUrl)
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
        } catch (e: Exception){
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

