package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.services.Pluggable
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
) : EmailClient {
    companion object : Pluggable<EmailSettings, EmailClient>() {
        init {
            EmailSettings.register("test") { TestEmailClient }
            EmailSettings.register("console") { ConsoleEmailClient }
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
                                fromEmail = params["fromEmail"]?.first() ?: it.fromEmail
                                ?: throw IllegalStateException("SMTP Email requires a fromEmail to be set.")
                            )
                        )
                    }
                    ?: throw IllegalStateException("Invalid SMTP URL. The URL should match the pattern: smtp://[username]:[password]@[host]:[port]?[params]\nAvailable params are: fromEmail")
            }
        }
    }

    private var backing: EmailClient? = null
    val wraps: EmailClient
        get() {
            if(backing == null) backing = parse(url.substringBefore("://"), this)
            return backing!!
        }

    override suspend fun healthCheck(): HealthStatus = wraps.healthCheck()
    override suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) = wraps.sendBulk(template, personalizations)
    override suspend fun send(email: Email) = wraps.send(email)
}

