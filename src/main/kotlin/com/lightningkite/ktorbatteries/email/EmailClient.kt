package com.lightningkite.ktorbatteries.email

interface EmailClient {
    suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String? = null,
        attachments: List<Attachment> = listOf(),
    )
}