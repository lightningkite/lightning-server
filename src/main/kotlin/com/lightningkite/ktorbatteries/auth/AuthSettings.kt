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
    }
) {
    companion object : SettingSingleton<AuthSettings>() {
        const val userIdKey: String = "userId"
    }

    init {
        instance = this
    }
}

private val availableCharacters =
    "0123456789qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM~!@#%^&*()_+`-=[]{};':,./<>?"