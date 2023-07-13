package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import kotlinx.coroutines.runBlocking

/**
 * An email client that will send real emails through the Mailgun API.
 */
class MailgunEmailClient(
    val key: String,
    val domain: String,
) : EmailClient {
    override suspend fun send(email: Email) {
        val parts = email.attachments.map {
            FormPart(if (it.inline) "inline" else "attachment", ChannelProvider(
                size = it.content.length,
                block = { runBlocking { it.content.stream() }.toByteReadChannel() }
            ))
        }
        val result = client.submitFormWithBinaryData(
            url = "https://api.mailgun.net/v3/$domain/messages",
            formData = formData {
                append("from", "${generalSettings().projectName} <noreply@$domain>")
                email.to.forEach {
                    append("to", it.toString())
                }
                append("subject", email.subject)
                append("text", email.plainText)
                append("html", email.html)
                append("o:tracking", "false")
                email.customHeaders.entries.forEach {
                    append("h:${it.first}", it.second)
                }
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
