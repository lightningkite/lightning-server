package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encodeToString
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
    val expirationMilliseconds: Long = Duration.ofDays(365).toMillis(),
    val emailExpirationMilliseconds: Long = Duration.ofHours(1).toMillis(),
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

    inline fun <reified T> token(subject: T, expireDuration: Long? = expirationMilliseconds): String = token(serializerOrContextual(), subject, expireDuration ?: expirationMilliseconds)
    fun <T> token(serializer: KSerializer<T>, subject: T, expireDuration: Long = expirationMilliseconds): String =
        Serialization.json.encodeJwt(hasher, serializer, subject, expirationMilliseconds / 1000, issuer ?: generalSettings().publicUrl, audience ?: generalSettings().publicUrl)

    inline fun <reified T> verify(token: String): T = verify(serializerOrContextual(), token)
    fun <T> verify(serializer: KSerializer<T>, token: String): T {
        return try {
            Serialization.json.decodeJwt(hasher, serializer, token, audience ?: generalSettings().publicUrl)
        } catch (e: JwtException) {
            throw UnauthorizedException(
                body = "Invalid token $token: ${e.message}",
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

    companion object {
        const val userIdKey: String = "userId"
    }
}

