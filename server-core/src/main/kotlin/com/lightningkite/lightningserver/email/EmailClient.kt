package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

/**
 * An interface for sending emails. This is used directly by the EmailSettings to abstract the implementation of
 * sending emails away, so it can go to multiple places.
 */
interface EmailClient : HealthCheckable {
    suspend fun sendHtml(
        subject: String,
        to: List<String>,
        html: String,
        plainText: String = html.emailApproximatePlainText(),
        attachments: List<Attachment> = listOf(),
    )
    suspend fun sendPlainText(
        subject: String,
        to: List<String>,
        plainText: String,
        attachments: List<Attachment> = listOf(),
    ) = sendHtml(subject = subject, to = to, html = plainText.emailPlainTextToHtml(), plainText = plainText, attachments = attachments)

    @Deprecated("Prefer sendHtml", ReplaceWith("sendHtml(subject = subject, to = to, html = htmlMessage, plainText = message, attachments = attachments)"))
    suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String? = null,
        attachments: List<Attachment> = listOf(),
    ) = sendHtml(subject = subject, to = to, html = htmlMessage ?: message.emailPlainTextToHtml(), plainText = message, attachments = attachments)

    override suspend fun healthCheck(): HealthStatus {
        try {
            sendPlainText("Test Email", to = listOf("test@test.com"), plainText = "This is a test message")
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}
