package com.lightningkite.lightningserver.email

/**
 * An email client that will simply print out everything to the console
 */

object ConsoleEmailClient : EmailClient {
    data class Email(
        val subject: String,
        val to: List<String>,
        val message: String,
        val htmlMessage: String?,
        val attachments: List<Attachment>
    )

    override suspend fun send(
        subject: String, to: List<String>, message: String, htmlMessage: String?, attachments: List<Attachment>
    ) {
        println(buildString {
            appendLine("-----EMAIL-----")
            appendLine(subject)
            appendLine()
            appendLine(to.joinToString())
            appendLine()
//            htmlMessage?.let{
//                appendLine(it)
//                appendLine()
//            }
            appendLine(message)
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
            lastEmailSent = Email(
                subject = subject,
                to = to,
                message = message,
                htmlMessage = htmlMessage,
                attachments = attachments,
            )
        })
    }

    var lastEmailSent: Email? = null
        private set
}