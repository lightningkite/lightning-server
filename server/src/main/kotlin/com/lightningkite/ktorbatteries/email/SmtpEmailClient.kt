package com.lightningkite.ktorbatteries.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.mail.DefaultAuthenticator
import org.apache.commons.mail.EmailAttachment
import org.apache.commons.mail.HtmlEmail
import org.apache.commons.mail.MultiPartEmail
import org.apache.commons.mail.SimpleEmail
import javax.mail.Authenticator

/**
 * An email client that will send real emails through SMTP.
 */
class SmtpEmailClient(val smtpConfig: SmtpConfig) : EmailClient {
    override suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String?,
        attachments: List<Attachment>
    ) {
        val email = if (htmlMessage == null) {
            if (attachments.isEmpty()) {
                SimpleEmail().setMsg(message)
            } else {
                val multiPart = MultiPartEmail()
                multiPart.setMsg(message)
                attachments.forEach {
                    val attachment = EmailAttachment()
                    attachment.disposition = EmailAttachment.ATTACHMENT
                    attachment.description = it.description
                    attachment.name = it.name
                    when (it) {
                        is Attachment.Remote -> {
                            attachment.url = it.url
                        }
                        is Attachment.Local -> {
                            attachment.path = it.file.absolutePath
                        }
                    }
                    multiPart.attach(attachment)
                }
                multiPart
            }
        } else{
            val email = HtmlEmail()
            email.setHtmlMsg(htmlMessage)
            attachments.forEach {
                val attachment = EmailAttachment()
                attachment.disposition = EmailAttachment.ATTACHMENT
                attachment.description = it.description
                attachment.name = it.name
                when (it) {
                    is Attachment.Remote -> {
                        attachment.url = it.url
                    }
                    is Attachment.Local -> {
                        attachment.path = it.file.absolutePath
                    }
                }
                email.attach(attachment)
            }
            email
        }
        email.hostName = smtpConfig.hostName
        if (smtpConfig.username != null || smtpConfig.password != null) {
            if (smtpConfig.username == null || smtpConfig.password == null) throw Exception("Missing Authentication")
            email.setAuthentication(smtpConfig.username, smtpConfig.password)
        }
        email.setSmtpPort(smtpConfig.port)
        email.isSSLOnConnect = smtpConfig.useSSL
        email.setFrom(smtpConfig.fromEmail)
        email.subject = subject
        email.addTo(*to.toTypedArray())
        withContext(Dispatchers.IO) {
            email.send()
        }
    }
}