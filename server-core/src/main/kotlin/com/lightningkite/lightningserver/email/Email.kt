package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.files.download
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpContentAndHeaders
import com.lightningkite.lightningserver.http.HttpHeaders
import io.ktor.client.request.*
import io.ktor.util.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

data class Email(
    val subject: String,
    val from: EmailLabeledValue? = null,
    val to: List<EmailLabeledValue>,
    val cc: List<EmailLabeledValue> = listOf(),
    val bcc: List<EmailLabeledValue> = listOf(),
    val html: String,
    val plainText: String = html.emailApproximatePlainText(),
    val attachments: List<Attachment> = listOf(),
    val customHeaders: HttpHeaders = HttpHeaders.EMPTY,
) {
    constructor(
        subject: String,
        from: EmailLabeledValue? = null,
        to: List<EmailLabeledValue>,
        cc: List<EmailLabeledValue> = listOf(),
        bcc: List<EmailLabeledValue> = listOf(),
        plainText: String,
        attachments: List<Attachment> = listOf(),
        customHeaders: HttpHeaders = HttpHeaders.EMPTY,
    ):this(
        subject = subject,
        from = from,
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
        val content: HttpContent
    )
}

data class EmailPersonalization(
    val to: List<EmailLabeledValue>,
    val cc: List<EmailLabeledValue> = listOf(),
    val bcc: List<EmailLabeledValue> = listOf(),
    val substitutions: Map<String, String> = mapOf(),
    val customHeaders: HttpHeaders = HttpHeaders.EMPTY,
) {
    operator fun invoke(email: Email): Email {
        return email.copy(
            customHeaders = email.customHeaders + customHeaders,
            to = to,
            html = run {
                var current = email.html
                for((key, value) in substitutions) {
                    current.replace(key, value.escapeHTML())
                }
                current
            },
            subject = run {
                var current = email.subject
                for((key, value) in substitutions) {
                    current.replace(key, value)
                }
                current
            },
            plainText = run {
                var current = email.plainText
                for((key, value) in substitutions) {
                    current.replace(key, value)
                }
                current
            }
        )
    }
}

@Serializable
data class EmailLabeledValue(
    val value: String,
    val label: String = ""
) {
    companion object {
        fun parse(raw: String) = EmailLabeledValue(raw.substringBefore('<', ""), raw.substringAfter('<').substringBefore('>'))
    }

    override fun toString(): String = "$label <$value>"
}

//data class Email(
//    val headers: HttpHeaders,
//    val content: HttpContent,
//) {
//    fun toHttpContentAndHeaders() = HttpContentAndHeaders(headers, content)
//    constructor(
//        subject: String,
//        from: EmailLabeledValue? = null,
//        to: List<EmailLabeledValue>,
//        cc: List<EmailLabeledValue> = listOf(),
//        html: String,
//        plainText: String = html.emailApproximatePlainText(),
//        attachments: List<Attachment> = listOf(),
//        inReplyTo: String? = null,
//    ):this(
//        HttpHeaders {
//            from?.let { set(EmailHeader.From, it.toString()) }
//            set(EmailHeader.Subject, subject)
//            to.forEach { set(EmailHeader.To, it.toString()) }
//            cc.forEach { set(EmailHeader.Cc, it.toString()) }
//            inReplyTo?.let { set(EmailHeader.InReplyTo, it) }
//        },
//        HttpContent.Multipart(ContentType.MultiPart.Mixed, flow {
//            emit(
//                HttpContentAndHeaders(
//                    headers = HttpHeaders(),
//                    content = HttpContent.Multipart(
//                        ContentType.MultiPart.Alternative, flowOf(
//                            HttpContentAndHeaders(
//                                headers = HttpHeaders(),
//                                content = HttpContent.Text(html, ContentType.Text.Html)
//                            ),
//                            HttpContentAndHeaders(
//                                headers = HttpHeaders(),
//                                content = HttpContent.Text(plainText, ContentType.Text.Plain)
//                            )
//                        )
//                    )
//                )
//            )
//            for (a in attachments) {
//                emit(HttpContent.Multipart.dataItem(
//                    key = if (a.inline) "inline" else "attachment",
//                    filename = a.name,
//                    headers = HttpHeaders(),
//                    content = when (a) {
//                        is Attachment.Local -> HttpContent.file(a.file)
//                        is Attachment.Remote -> client.get(a.url).download().let { HttpContent.file(it) }
//                    }
//                ))
//            }
//        })
//    )
//
//    constructor(
//        subject: String,
//        from: EmailLabeledValue? = null,
//        to: List<EmailLabeledValue>,
//        cc: List<EmailLabeledValue> = listOf(),
//        plainText: String,
//        attachments: List<Attachment> = listOf(),
//        inReplyTo: String? = null,
//    ):this(
//        subject = subject,
//        from = from,
//        to = to,
//        cc = cc,
//        plainText = plainText,
//        html = plainText.emailPlainTextToHtml(),
//        attachments = attachments,
//        inReplyTo = inReplyTo,
//    )
//
//    constructor(
//        subject: String,
//        from: String? = null,
//        to: List<String>,
//        cc: List<String> = listOf(),
//        html: String,
//        plainText: String = html.emailApproximatePlainText(),
//        attachments: List<Attachment> = listOf(),
//        inReplyTo: String? = null,
//    ):this(
//        subject = subject,
//        from = from?.let { EmailLabeledValue(it, it) },
//        to = to.map { EmailLabeledValue(it, it) },
//        cc = cc.map { EmailLabeledValue(it, it) },
//        plainText = plainText,
//        html = plainText.emailPlainTextToHtml(),
//        attachments = attachments,
//        inReplyTo = inReplyTo,
//    )
//
//    constructor(
//        subject: String,
//        from: String? = null,
//        to: List<String>,
//        cc: List<String> = listOf(),
//        plainText: String,
//        attachments: List<Attachment> = listOf(),
//        inReplyTo: String? = null,
//    ):this(
//        subject = subject,
//        from = from?.let { EmailLabeledValue(it, it) },
//        to = to.map { EmailLabeledValue(it, it) },
//        cc = cc.map { EmailLabeledValue(it, it) },
//        plainText = plainText,
//        html = plainText.emailPlainTextToHtml(),
//        attachments = attachments,
//        inReplyTo = inReplyTo,
//    )
//}

