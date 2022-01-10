package com.lightningkite.ktorkmongo.email

import kotlinx.html.InputType
import org.apache.commons.mail.EmailAttachment
import org.apache.commons.mail.HtmlEmail
import org.apache.commons.mail.MultiPartEmail
import org.apache.commons.mail.SimpleEmail

class SmtpEmailClient(val smtpConfig: SmtpConfig) : EmailClient {
    override fun sendEmail(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String?,
        attachments: List<Attachment>
    ) {
        val email = if (htmlMessage == null) {
            if(attachments.isEmpty()){
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
        if(smtpConfig.username != null){
            email.setAuthentication(smtpConfig.username, smtpConfig.password!!)
        }
        email.setSmtpPort(smtpConfig.port)
        email.isSSLOnConnect = smtpConfig.useSSL
        email.setFrom(smtpConfig.fromEmail)
        email.subject = subject
        email.addTo(*to.toTypedArray())

        email.send()
    }
}