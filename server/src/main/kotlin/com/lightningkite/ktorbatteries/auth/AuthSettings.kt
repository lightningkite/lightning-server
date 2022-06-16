package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.email.ConsoleEmailClient
import com.lightningkite.ktorbatteries.email.EmailClient
import com.lightningkite.ktorbatteries.email.EmailSettings
import com.lightningkite.ktorbatteries.email.SmtpEmailClient
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Duration
import java.util.*

/**
 * AuthSettings holds the values required to setup JWT Authentication.
 * This will be used by nearly every function in the auth package.
 * @param authDomain Used in cookies as the domain
 * @param jwtAudience Used in a JWT claim to help identify JWTs
 * @param jwtIssuer Used in a JWT claim to help identify JWTs
 * @param jwtRealm Used to define a realm that gets set in the header WWW-Authenticate
 * @param jwtExpirationMilliseconds The default expiration for tokens. This can be overridden for a specific token.
 * @param jwtSecret THis should be a long and complicated String. The jwtSecret should never be shared since it is what's used to sign JWTs.
 * @param oauth will define what external services you will allow to authenticate through.
 */
@Serializable
data class AuthSettings(
    val authDomain: String? = null,
    val jwtAudience: String = "user",
    val jwtIssuer: String = GeneralServerSettings.instance.publicUrl,
    val jwtRealm: String = "ServerAccess",
    val jwtExpirationMilliseconds: Long? = Duration.ofDays(365).toMillis(),
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

    companion object : SettingSingleton<AuthSettings>() {
        const val userIdKey: String = "userId"
    }

    init {
        instance = this
    }
}

private val availableCharacters =
    "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM~!@#%^&*()_+`-=[]{};':,./<>?"