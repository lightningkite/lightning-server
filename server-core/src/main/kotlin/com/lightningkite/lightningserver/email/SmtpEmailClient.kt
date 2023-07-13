package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.apache.commons.mail.EmailAttachment
import org.apache.commons.mail.HtmlEmail
import java.util.*
import javax.activation.DataHandler
import javax.mail.Address
import javax.mail.Authenticator
import javax.mail.Multipart
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * An email client that will send real emails through SMTP.
 */
class SmtpEmailClient(val smtpConfig: SmtpConfig) : EmailClient {
    val session = Session.getInstance(Properties().apply {
        put("mail.smtp.user", smtpConfig.username)
        put("mail.smtp.host", smtpConfig.hostName)
        put("mail.smtp.port", smtpConfig.port)
        put("mail.smtp.auth", true)
        put("mail.smtp.ssl.enable", smtpConfig.port == 465)
        put("mail.smtp.starttls.enable", smtpConfig.port == 587)
        put("mail.smtp.starttls.required", smtpConfig.port == 587)
    }, object: Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication = PasswordAuthentication(
            smtpConfig.username,
            smtpConfig.password
        )
    })
    val from by lazy { EmailLabeledValue(smtpConfig.fromEmail, generalSettings().projectName) }
    override suspend fun send(email: Email) {
        Transport.send(email.copy(from = email.from ?: from).toJavaX(session))
    }
}