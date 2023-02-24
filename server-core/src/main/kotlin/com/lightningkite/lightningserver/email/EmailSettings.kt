package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.lang.IllegalStateException

/**
 * EmailSettings defines where to send emails, and any credentials that may be required to do so.
 * There are two options currently with email. You can send it to the console, or you can use SMTP to send real emails.
 *
 * @param url A string containing everything needed to connect to an email server. The format is defined by the EmailClient that will consume it.
 *  For SMTP: smtp://username:password@host:port
 *  For mailgun: mailgun://key@domain
 *  For Console: console
 * @param fromEmail Required by at least the SMTP option. This will be the email that recipients see as the sender.
 */
@Serializable
data class EmailSettings(
    val url: String = "console",
    val fromEmail: String? = null,
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
                     SmtpConfig(
                        hostName = urlHost.substringBefore(':'),
                        port = port,
                        username = urlAuth.substringBefore(':'),
                        password = urlAuth.substringAfter(':'),
                        useSSL = port != 25,
                        fromEmail = it.fromEmail ?: throw IllegalStateException("SMTP Email requires a fromEmail to be set.")
                    )
                )
            }
        }
    }

    override fun invoke(): EmailClient = EmailSettings.parse(url.substringBefore("://"), this)

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
