package com.lightningkite.ktorbatteries.email

/**
 * An interface for sending emails. This is used directly by the EmailSettings to abstract the implementation of
 * sending emails away, so it can go to multiple places.
 */
interface EmailClient {
    suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String? = null,
        attachments: List<Attachment> = listOf(),
    )
}