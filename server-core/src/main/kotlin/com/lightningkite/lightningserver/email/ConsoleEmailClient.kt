package com.lightningkite.lightningserver.email

/**
 * An email client that will simply print out everything to the console
 */

object ConsoleEmailClient : EmailClient {
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
    var onEmailSent: (Email)->Unit = {}

    override suspend fun sendHtml(
        subject: String,
        to: List<String>,
        html: String,
        plainText: String,
        attachments: List<Attachment>
    ) {
        println(buildString {
            appendLine("-----EMAIL-----")
            appendLine(subject)
            appendLine()
            appendLine(to.joinToString())
            appendLine()
            appendLine(plainText)
            appendLine()
            attachments.forEach {
                appendLine(it.name)
                when (it) {
                    is Attachment.Local -> appendLine(it.file)
                    is Attachment.Remote -> appendLine(it.url)
                }
                appendLine(it.description)
                appendLine()
            }
            val e = Email(
                subject = subject,
                to = to,
                plainText = plainText,
                html = html,
                attachments = attachments,
            )
            onEmailSent(e)
            lastEmailSent = e
        })
    }

    var lastEmailSent: Email? = null
        private set
}