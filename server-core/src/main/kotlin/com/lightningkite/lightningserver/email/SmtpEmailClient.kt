package com.lightningkite.lightningserver.email

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.mail.EmailAttachment
import org.apache.commons.mail.HtmlEmail
import org.apache.commons.mail.MultiPartEmail
import org.apache.commons.mail.SimpleEmail

/**
 * An email client that will send real emails through SMTP.
 */
class SmtpEmailClient(val smtpConfig: SmtpConfig) : EmailClient {
    override suspend fun sendHtml(
        subject: String,
        to: List<String>,
        html: String,
        plainText: String,
        attachments: List<Attachment>
    ) {
        val email = HtmlEmail()
        email.setHtmlMsg(html)
        email.setTextMsg(plainText)
        attachments.forEach {
            val attachment = EmailAttachment()
            attachment.disposition = if (it.inline) EmailAttachment.INLINE else EmailAttachment.ATTACHMENT
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
        email.hostName = smtpConfig.hostName
        if (smtpConfig.username != null || smtpConfig.password != null) {
            if (smtpConfig.username == null || smtpConfig.password == null) throw Exception("Missing Authentication")
            email.setAuthentication(smtpConfig.username, smtpConfig.password)
        }
        email.setSmtpPort(smtpConfig.port)
        email.isSSLOnConnect = smtpConfig.useSSL
        email.setFrom(smtpConfig.fromEmail)
        email.subject = subject
//        email.addHeader("X-SES-LIST-MANAGEMENT-OPTIONS", "contactListName; topic=topicName")
        email.addTo(*to.toTypedArray())
        println("Ready to send...")
        withContext(Dispatchers.IO) {
            email.send()
        }
        println("Email sent")
    }
}