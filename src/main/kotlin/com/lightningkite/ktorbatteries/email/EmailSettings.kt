package com.lightningkite.ktorbatteries.email

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.files.FilesSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class EmailSettings(
    val option: EmailClientOption = EmailClientOption.Console,
    val smtp: SmtpConfig? = null
) {
    @Transient
    val emailClient: EmailClient = when (option) {
        EmailClientOption.Console -> ConsoleEmailClient
        EmailClientOption.Smtp -> SmtpEmailClient(
            smtp
                ?: throw IllegalArgumentException("Option SMTP was requested, but no additional information was present under the 'smtp' key.")
        )
    }

    companion object: SettingSingleton<EmailSettings>()
    init { instance = this }
}

val email: EmailClient get() = EmailSettings.instance.emailClient

@Serializable
enum class EmailClientOption {
    Console,
    Smtp,
}

@Serializable
data class SmtpConfig(
    val hostName: String = "",
    val port: Int = 25,
    val username: String? = null,
    val password: String? = null,
    val useSSL: Boolean = true,
    val fromEmail: String = "",
)