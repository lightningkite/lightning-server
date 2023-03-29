package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.client
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*

/**
 * An email client that will send real emails through SMTP.
 */
class MailgunEmailClient(
    val key: String,
    val domain: String,
) : EmailClient {
    override suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String?,
        attachments: List<Attachment>
    ) {
        val parts = attachments.map {
            when (it) {
                is Attachment.Local -> FormPart(if (it.inline) "inline" else "attachment", ChannelProvider(
                    size = it.file.length(),
                    block = { it.file.readChannel() }
                ))

                is Attachment.Remote -> {
                    val result = client.get(it.url)
                    val content = result.bodyAsChannel().toByteArray()
                    FormPart(if (it.inline) "inline" else "attachment", content)
                }
            }
        }
        val result = client.submitFormWithBinaryData(
            url = "https://api.mailgun.net/v3/$domain/messages",
            formData = formData {
                append("from", "noreply@$domain")
                to.forEach {
                    append("to", it)
                }
                append("subject", subject)
                append("text", message)
                htmlMessage?.let {
                    append("html", it)
                }
                append("o:tracking", "false")
                parts.forEach { append(it) }
            },
            block = {
                basicAuth("api", key)
            }
        )
        if (!result.status.isSuccess())
            throw Exception("Got status ${result.status}: ${result.bodyAsText()}")
    }
}
