package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.http.HttpHeaders
import io.ktor.util.*

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
            cc = cc,
            bcc = bcc,
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