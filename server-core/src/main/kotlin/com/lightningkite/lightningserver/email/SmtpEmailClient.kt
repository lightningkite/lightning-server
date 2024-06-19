package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.settings.generalSettings
import java.util.*
import jakarta.mail.Authenticator
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress

/**
 * An email client that will send real emails through SMTP.
 */
class SmtpEmailClient(val smtpConfig: SmtpConfig) : EmailClient {

    val session = Session.getInstance(
        Properties().apply {
            smtpConfig.username?.let { username ->
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
        if(email.to.isEmpty() && email.cc.isEmpty() && email.bcc.isEmpty()) return
        Transport.send(
            email.copy(
                fromEmail = email.fromEmail ?: smtpConfig.fromEmail,
                fromLabel = email.fromLabel ?: generalSettings().projectName
            ).toJavaX(session)
        )
    }

    override suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) {
        if (personalizations.isEmpty()) return
        session.transport
            .also { it.connect() }
            .use { transport ->
                personalizations
                    .asSequence()
                    .map {
                        it(template).copy(
                            fromEmail = template.fromEmail ?: smtpConfig.fromEmail,
                            fromLabel = template.fromLabel ?: generalSettings().projectName
                        )
                    }
                    .forEach { email ->
                        transport.sendMessage(
                            email.toJavaX(session).also { it.saveChanges() },
                            email.to
                                .plus(email.cc)
                                .plus(email.bcc)
                                .map { InternetAddress(it.value, it.label) }
                                .toTypedArray()
                                .also { if (it.isEmpty()) return@forEach }
                        )
                    }
            }
    }
}
