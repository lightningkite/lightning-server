package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.Serializable

/**
 * EmailSettings defines where to send emails, and any credentials that may be required to do so.
 * There are two live options built in. You can use SMTP credentials to send the email through Apache Commons, or through the MailGun API
 *
 * @param url A string containing everything needed to connect to an email server. The format is defined by the EmailClient that will consume it.
 *  For SMTP: smtp://username:password@host:port*|fromEmail*    *:Optional items
 *  For mailgun: mailgun://key@domain
 *  For Console: console
 *  For Tests: test
 * @param fromEmail Required by at least the SMTP option. This will be the email that recipients see as the sender.
 */
@Serializable
data class EmailSettings(
    val url: String = "console",
    val fromEmail: String? = null,
) : () -> EmailClient {
    companion object : Pluggable<EmailSettings, EmailClient>() {
        init {
            EmailSettings.register("test") { TestEmailClient }
            EmailSettings.register("console") { ConsoleEmailClient }
            EmailSettings.register("mailgun") {
                Regex("""mailgun://(?<key>[^@]+)@(?<domain>.+)""").matchEntire(it.url)?.let { match ->
                    MailgunEmailClient(
                        match.groups["key"]!!.value,
                        match.groups["domain"]!!.value
                    )
                }
                    ?: throw IllegalStateException("Invalid Mailgun URL. The URL should match the pattern: mailgun://[key]@[domain]")
            }
            EmailSettings.register("smtp") {
                Regex("""smtp://(?:(?<username>[^:]+):(?<password>.+)@)?(?<host>[^:@]+):(?<port>[0-9]+)(?:\?(?<params>.*))?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        val port = match.groups["port"]!!.value.toInt()
                        val params = EmailSettings.parseParameterString(match.groups["params"]?.value ?: "")
                        SmtpEmailClient(
                            SmtpConfig(
                                hostName = match.groups["host"]!!.value,
                                port = port,
                                username = match.groups["username"]?.value,
                                password = match.groups["password"]?.value,
                                useSSL = port != 25,
                                fromEmail = params["fromEmail"]?.first() ?: it.fromEmail
                                ?: throw IllegalStateException("SMTP Email requires a fromEmail to be set.")
                            )
                        )
                    }
                    ?: throw IllegalStateException("Invalid SMTP URL. The URL should match the pattern: smtp://[username]:[password]@[host]:[port]?[params]\nAvailable params are: fromEmail")
            }
        }
    }

    override fun invoke(): EmailClient = EmailSettings.parse(url.substringBefore("://"), this)

}

@Serializable
data class SmtpConfig(
    val hostName: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val useSSL: Boolean,
    val fromEmail: String,
)
