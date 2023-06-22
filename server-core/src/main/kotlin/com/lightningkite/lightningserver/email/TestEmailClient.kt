package com.lightningkite.lightningserver.email

/**
 * A concrete implementation of EmailClient that will is similar to ConsoleEmailClient but with more options
 * You can turn off the console printing
 * It stores the last message sent
 * You can set a lambda for getting send events
 * This is useful for Unit Tests
 */

object TestEmailClient : EmailClient {
    data class Email(
        val subject: String,
        val to: List<String>,
        val plainText: String,
        val html: String,
        val attachments: List<Attachment>
    ) {
        val message: String get() = plainText
        val htmlMessage: String get() = html
    }

    var onEmailSent: ((Email)->Unit)? = null
    var lastEmailSent: Email? = null
        private set
    var printToConsole: Boolean = false

    override suspend fun sendHtml(
        subject: String,
        to: List<String>,
        html: String,
        plainText: String,
        attachments: List<Attachment>
    ) {
        val e = Email(
            subject = subject,
            to = to,
            plainText = plainText,
            html = html,
            attachments = attachments,
        )
        lastEmailSent = e
        onEmailSent?.invoke(e)
        if (printToConsole)
            ConsoleEmailClient.sendHtml(subject, to, html, plainText, attachments)
    }

}