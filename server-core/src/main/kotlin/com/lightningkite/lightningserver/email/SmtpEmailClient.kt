package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.settings.generalSettings
import java.util.*
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport

/**
 * An email client that will send real emails through SMTP.
 */
class SmtpEmailClient(val smtpConfig: SmtpConfig) : EmailClient {

    val session = Session.getInstance(
        Properties().apply {
            smtpConfig.username?.let{ username ->
                put("mail.smtp.user", username)
            }
            put("mail.smtp.host", smtpConfig.hostName)
            put("mail.smtp.port", smtpConfig.port)
            put("mail.smtp.auth", smtpConfig.username != null && smtpConfig.password != null)
            put("mail.smtp.ssl.enable", smtpConfig.port == 465)
            put("mail.smtp.starttls.enable", smtpConfig.port == 587)
            put("mail.smtp.starttls.required", smtpConfig.port == 587)
        },
        if (smtpConfig.username != null && smtpConfig.password != null)
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication = PasswordAuthentication(
                    smtpConfig.username,
                    smtpConfig.password
                )
            }
        else
            null
    )

    override suspend fun send(email: Email) {
        Transport.send(
            email.copy(
                fromEmail = email.fromEmail ?: smtpConfig.fromEmail,
                fromLabel = email.fromLabel ?: generalSettings().projectName
            ).toJavaX(session)
        )
    }
}