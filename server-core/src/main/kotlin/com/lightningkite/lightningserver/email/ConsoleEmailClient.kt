package com.lightningkite.lightningserver.email

/**
 * A concrete implementation of EmailClient that will simply print out everything to the console
 * This is useful for local development
 */

object ConsoleEmailClient : EmailClient {

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
            if(plainText.isNotBlank())
                appendLine(plainText)
            else {
                appendLine(html.emailApproximatePlainText())
            }
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
        })
    }

}