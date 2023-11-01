package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

/**
 * An interface for sending emails. This is used directly by the EmailSettings to abstract the implementation of
 * sending emails away, so it can go to multiple places.
 */
interface EmailClient : HealthCheckable {
    suspend fun send(email: Email)
    suspend fun sendBulk(template: Email, personalizations: List<EmailPersonalization>) = personalizations.forEach {
        send(it(template))
    }

    override suspend fun healthCheck(): HealthStatus {
        try {
            send(
                Email(
                    subject = "Test Email",
                    to = listOf(EmailLabeledValue("test@test.com")),
                    plainText = "This is a test message"
                )
            )
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}
