package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

/**
 * An interface for sending emails. This is used directly by the EmailSettings to abstract the implementation of
 * sending emails away, so it can go to multiple places.
 */
interface EmailClient : HealthCheckable {
    suspend fun send(
        subject: String,
        to: List<String>,
        message: String,
        htmlMessage: String? = null,
        attachments: List<Attachment> = listOf(),
    )

    override suspend fun healthCheck(): HealthStatus {
        try {
            send("Test Email", to = listOf("test@test.com"), message = "This is a test message")
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}