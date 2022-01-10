package com.lightningkite.ktorkmongo.email

import kotlinx.serialization.Serializable

@Serializable
data class EmailSettings(
    val option: EmailClientOption = EmailClientOption.CONSOLE,
    val smtp: SmtpConfig? = null
) {
    val emailClient: EmailClient = when(option) {
        EmailClientOption.CONSOLE -> ConsoleEmailClient
        EmailClientOption.SMTP -> SmtpEmailClient(smtp ?: throw IllegalArgumentException("Option SMTP was requested, but no additional information was present under the 'smtp' key."))
    }

    companion object {
        var instance: EmailSettings = EmailSettings()
    }
    init { instance = this }
}

@Serializable
enum class EmailClientOption {
    CONSOLE,
    SMTP,
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