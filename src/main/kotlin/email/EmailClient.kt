package com.lightningkite.ktorkmongo.email

interface EmailClient {
    fun sendEmail(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String? = null,
        attachments: List<Attachment> = listOf(),
    )
}