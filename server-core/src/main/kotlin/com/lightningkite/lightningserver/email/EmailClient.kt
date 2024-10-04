package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

/**
 * An interface for sending emails. This is used directly by the EmailSettings to abstract the implementation of
 * sending emails away, so it can go to multiple places.
 */
@Suppress("DEPRECATION")
interface EmailClient : HealthCheckable {
    suspend fun send(email: Email)
    suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) = personalizations.forEach {
        send(it(template))
    }
    suspend fun sendBulk(emails: Collection<Email>) = emails.forEach {
        send(it)
    }

    @Deprecated(
        "Prefer send(Email)"
    )
    suspend fun sendHtml(
        subject: String,
        to: List<String>,
        html: String,
        plainText: String = html.emailApproximatePlainText(),
        attachments: List<Attachment> = listOf(),
    ) = send(
        Email(
            subject = subject,
            to = to.map { EmailLabeledValue(it) },
            html = html,
            plainText = plainText,
            attachments = attachments.map { it.convert() },
        )
    )

    @Deprecated(
        "Prefer send(Email)"
    )
    suspend fun sendPlainText(
        subject: String,
        to: List<String>,
        plainText: String,
        attachments: List<Attachment> = listOf(),
    ) = send(
        Email(
            subject = subject,
            to = to.map { EmailLabeledValue(it) },
            plainText = plainText,
            attachments = attachments.map { it.convert() },
        )
    )

    @Deprecated(
        "Prefer send(Email)"
    )
    suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String? = null,
        attachments: List<Attachment> = listOf(),
    ) = send(
        Email(
            subject = subject,
            to = to.map { EmailLabeledValue(it) },
            plainText = message,
            html = htmlMessage ?: message.emailPlainTextToHtml(),
            attachments = attachments.map { it.convert() },
        )
    )


    override suspend fun healthCheck(): HealthStatus {
        try {
            send(
                Email(
                    subject = "Test Email",
                    to = listOf(EmailLabeledValue("test@test.com")),
                    plainText = "This is a test message"
                )
            )
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}
