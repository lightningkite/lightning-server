package com.lightningkite.lightningserver.email

import com.lightningkite.Blob

data class Email(
    val subject: String,
    val fromEmail: String? = null,
    val fromLabel: String? = null,
    val to: List<EmailLabeledValue>,
    val cc: List<EmailLabeledValue> = listOf(),
    val bcc: List<EmailLabeledValue> = listOf(),
    val html: String,
    val plainText: String = html.emailApproximatePlainText(),
    val attachments: List<Attachment> = listOf(),
    val customHeaders: List<Pair<String, String>> = listOf(),
) {
    constructor(
        subject: String,
        fromEmail: String? = null,
        fromLabel: String? = null,
        to: List<EmailLabeledValue>,
        cc: List<EmailLabeledValue> = listOf(),
        bcc: List<EmailLabeledValue> = listOf(),
        plainText: String,
        attachments: List<Attachment> = listOf(),
        customHeaders: List<Pair<String, String>> = listOf(),
    ):this(
        subject = subject,
        fromEmail = fromEmail,
        fromLabel = fromLabel,
        to = to,
        cc = cc,
        bcc = bcc,
        html = plainText.emailPlainTextToHtml(),
        plainText = plainText,
        attachments = attachments,
        customHeaders = customHeaders,
    )
    data class Attachment(
        val inline: Boolean,
        val filename: String,
        val content: Blob
    )
}

