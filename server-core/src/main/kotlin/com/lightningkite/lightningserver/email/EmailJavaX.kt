package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContentAndHeaders
import com.lightningkite.lightningserver.http.HttpHeaders
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.activation.DataHandler
import javax.mail.Address
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.*
import javax.mail.util.ByteArrayDataSource


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

        is HttpContent.OutStream -> part.dataHandler =
            DataHandler(ByteArrayDataSource(content.stream(), content.type.toString()))

        is HttpContent.Stream -> part.dataHandler =
            DataHandler(ByteArrayDataSource(content.getStream(), content.type.toString()))

        is HttpContent.Text -> part.dataHandler =
            DataHandler(ByteArrayDataSource(content.string, content.type.toString()))
    }
}

suspend fun Email.toJavaX(session: Session): Message = MimeMessage(session).apply {
    val email = this@toJavaX
    subject = email.subject
    email.from?.let { setFrom(InternetAddress(it.value, it.label)) }
    email.to.forEach { setRecipient(Message.RecipientType.TO, InternetAddress(it.value, it.label)) }
    email.cc.forEach { setRecipient(Message.RecipientType.CC, InternetAddress(it.value, it.label)) }
    email.bcc.forEach { setRecipient(Message.RecipientType.BCC, InternetAddress(it.value, it.label)) }
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
                    emit(HttpContent.Multipart.dataItem(
                        key = if (a.inline) "inline" else "attachment",
                        filename = a.filename,
                        headers = HttpHeaders(),
                        content = a.content
                    ))
                }
            }
        )
    ).into(this)
    email.customHeaders
}
