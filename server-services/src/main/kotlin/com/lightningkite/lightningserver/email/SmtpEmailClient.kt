package com.lightningkite.lightningserver.email

import com.lightningkite.HeaderValue
import com.lightningkite.HttpHeader
import com.lightningkite.MimeType
import java.util.*
import javax.activation.DataHandler
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

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
                fromLabel = email.fromLabel ?: smtpConfig.fromEmailLabel
            ).toJavaX(session)
        )
    }

    fun Email.toJavaX(session: Session = Session.getDefaultInstance(Properties(), null)): Message = MimeMessage(session).apply {
        val email = this@toJavaX
        subject = email.subject
        email.fromEmail?.let { setFrom(InternetAddress(it, email.fromLabel)) }
        email.to.forEach { setRecipient(Message.RecipientType.TO, InternetAddress(it.value, it.label)) }
        email.cc.forEach { setRecipient(Message.RecipientType.CC, InternetAddress(it.value, it.label)) }
        email.bcc.forEach { setRecipient(Message.RecipientType.BCC, InternetAddress(it.value, it.label)) }
        setContent(MimeMultipart("mixed").apply {
            addBodyPart(MimeBodyPart().apply {
                setContent(MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply {
                        DataHandler(ByteArrayDataSource(plainText, MimeType.Text.Plain.string))
                    })
                    addBodyPart(MimeBodyPart().apply {
                        DataHandler(ByteArrayDataSource(html, MimeType.Text.Html.string))
                    })
                })
            })
            for(a in attachments) {
                addBodyPart(MimeBodyPart().apply {
                    addHeader(HttpHeader.ContentDisposition, HeaderValue("form-data", listOf("name" to (if (a.inline) "inline" else "attachment"), "filename" to a.filename)).string)
                    addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(a.content.read(), a.content.type.string))
                    })
                })
            }
        })
        email.customHeaders
    }

}