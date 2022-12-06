package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * EmailSettings defines where to send emails, and any credentials that may be required to do so.
 * There are two options currently with email. You can send it to the console, or you can use SMTP to send real emails.
 *
 * @param option An Enum defining where to send email. This can be "Console" or "Smtp"
 * @param smtp Required only if [option] is Smtp. These are the SMTP Credentials you wish to use to send real emails
 */
@Serializable
data class EmailSettings(
    val url: String = "old",
    val fromEmail: String = "",
    val option: EmailClientOption = EmailClientOption.Console,
    val smtp: SmtpConfig? = null
) : () -> EmailClient {
    companion object : Pluggable<EmailSettings, EmailClient>() {
        init {
            EmailSettings.register("console") { ConsoleEmailClient }
            EmailSettings.register("mailgun") {
                val urlWithoutProtocol = it.url.substringAfter("://")
                val key = urlWithoutProtocol.substringBefore('@')
                val domain = urlWithoutProtocol.substringAfter('@')
                MailgunEmailClient(
                    key,
                    domain
                )
            }
            EmailSettings.register("smtp") {
                val urlWithoutProtocol = it.url.substringAfter("://")
                val urlAuth = urlWithoutProtocol.substringBeforeLast('@')
                val urlHost = urlWithoutProtocol.substringAfterLast('@')
                val port = urlHost.substringAfter(':', "").toIntOrNull() ?: 22
                SmtpEmailClient(
                    it.smtp ?: SmtpConfig(
                        hostName = urlHost.substringBefore(':'),
                        port = port,
                        username = urlAuth.substringBefore(':'),
                        password = urlAuth.substringAfter(':'),
                        useSSL = port != 25,
                        fromEmail = it.fromEmail
                    )
                )
            }
            EmailSettings.register("old") {
                when (it.option) {
                    EmailClientOption.Console -> ConsoleEmailClient
                    EmailClientOption.Smtp -> SmtpEmailClient(
                        it.smtp
                            ?: throw IllegalArgumentException("Option SMTP was requested, but no additional information was present under the 'smtp' key.")
                    )
                }
            }
        }
    }

    override fun invoke(): EmailClient = EmailSettings.parse(url.substringBefore("://"), this)

    @Transient
    var sendEmailDuringTests: Boolean = false
}

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
