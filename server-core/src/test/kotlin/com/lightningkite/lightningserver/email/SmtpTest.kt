package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Test
import java.io.File

class SmtpTest {
    @Test
    fun testSmtp() {
        val credentials = File("local/test-smtp.json")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return
        }
        val client =
            SmtpEmailClient(credentials.readText().let { Serialization.json.decodeFromString(credentials.readText()) })
        runBlocking {
            client.send(
                Email(
                    subject = "Subject 2", fromLabel = "Joseph Ivie", fromEmail = "joseph@lightningkite.com",
                    to = listOf(EmailLabeledValue("joseph@lightningkite.com", "Joseph Ivie")),
                    html = "<p>Hello world!</p>",
                )
            )
        }
    }

    @Test
    fun testSendBulk(): Unit = runBlocking {

        val credentials = File("local/test-smtp.json")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return@runBlocking
        }

        val client =
            SmtpEmailClient(credentials.readText().let { Serialization.json.decodeFromString(credentials.readText()) })

        val email1 = EmailLabeledValue(
            "joseph@lightningkite.com",
            "Joseph Ivie One"
        )
        val email2 = EmailLabeledValue(
            "joseph+two@lightningkite.com",
            "Joseph Ivie Two"
        )
        val email3 = EmailLabeledValue(
            "joseph+three@lightningkite.com",
            "Joseph Ivie Three"
        )

        client.sendBulk(
            Email(
                subject = "Bulk Email Test", fromLabel = "Joseph Ivie", fromEmail = "joseph@lightningkite.com",
                to = emptyList(),
                html = "<p>Hello {{UserName}}!</p>",
            ),
            personalizations = listOf(
                EmailPersonalization(
                    to = listOf(email1),
                    cc = listOf(email2),
                    bcc = listOf(email3),
                    substitutions = mapOf("{{UserName}}" to email1.label)
                ),
                EmailPersonalization(
                    to = listOf(email2),
                    cc = listOf(email3),
                    bcc = listOf(email1),
                    substitutions = mapOf("{{UserName}}" to email2.label)
                ),
                EmailPersonalization(
                    to = listOf(email3),
                    cc = listOf(email1),
                    bcc = listOf(email2),
                    substitutions = mapOf("{{UserName}}" to email3.label)
                ),
                EmailPersonalization(
                    to = listOf(email1, email2, email3),
                    substitutions = mapOf(
                        "{{UserName}}" to listOf(
                            email1.label,
                            email2.label,
                            email3.label
                        ).joinToString { it })
                ),
            ),
        )

    }
}