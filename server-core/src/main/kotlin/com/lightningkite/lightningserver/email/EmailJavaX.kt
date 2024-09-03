package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContentAndHeaders
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.settings.generalSettings
import jakarta.activation.*
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.util.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.*


private suspend fun HttpContentAndHeaders.into(part: MimePart) {
    headers.entries.forEach {
        part.addHeader(it.first, it.second)
    }
    when (content) {
        is HttpContent.Multipart -> part.setContent(MimeMultipart(this.content.type.subtype).apply {
            content.parts.collect {
                addBodyPart(MimeBodyPart().apply {
                    it.into(this)
                })
            }
        })

        is HttpContent.Binary -> part.dataHandler =
            DataHandler(ByteArrayDataSource(content.bytes, content.type.toString()))

        is HttpContent.OutStream -> part.dataHandler = content.stream().use { stream ->
            DataHandler(ByteArrayDataSource(stream, content.type.toString()))
        }

        is HttpContent.Stream -> part.dataHandler = content.getStream().use { stream ->
            DataHandler(ByteArrayDataSource(stream, content.type.toString()))
        }

        is HttpContent.Text -> part.dataHandler =
            DataHandler(ByteArrayDataSource(content.string, content.type.toString()))
    }
}

suspend fun Email.toJavaX(session: Session = Session.getDefaultInstance(Properties(), null)): Message =
    MimeMessage(session).apply {
        val email = this@toJavaX
        subject = email.subject
        email.fromEmail?.let { setFrom(InternetAddress(it, email.fromLabel)) }
        email.to.map { InternetAddress(it.value, it.label) }
            .also { setRecipients(Message.RecipientType.TO, it.toTypedArray()) }
        email.cc.map { InternetAddress(it.value, it.label) }
            .also { setRecipients(Message.RecipientType.CC, it.toTypedArray()) }
        email.bcc.map { InternetAddress(it.value, it.label) }
            .also { setRecipients(Message.RecipientType.BCC, it.toTypedArray()) }
        HttpContentAndHeaders(
            headers = email.customHeaders,
            content = HttpContent.Multipart(
                ContentType.MultiPart.Mixed, flow {
                    emit(
                        HttpContentAndHeaders(
                            headers = HttpHeaders(),
                            content = HttpContent.Multipart(
                                ContentType.MultiPart.Alternative, flowOf(
                                    HttpContentAndHeaders(
                                        headers = HttpHeaders(),
                                        content = HttpContent.Text(plainText, ContentType.Text.Plain)
                                    ),
                                    HttpContentAndHeaders(
                                        headers = HttpHeaders(),
                                        content = HttpContent.Text(html, ContentType.Text.Html)
                                    ),
                                )
                            )
                        )
                    )
                    for (a in attachments) {
                        emit(
                            HttpContent.Multipart.dataItem(
                                key = if (a.inline) "inline" else "attachment",
                                filename = a.filename,
                                headers = HttpHeaders(),
                                content = a.content
                            )
                        )
                    }
                }
            )
        ).into(this)
        email.customHeaders
    }
