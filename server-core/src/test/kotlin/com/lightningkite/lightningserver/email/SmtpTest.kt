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
        val client = SmtpEmailClient(credentials.readText().let { Serialization.json.decodeFromString(credentials.readText()) })
        runBlocking {
            client.send(Email(
                subject = "Subject 2",
                from = EmailLabeledValue("joseph@lightningkite.com", "Joseph Ivie"),
                to = listOf(EmailLabeledValue("joseph@lightningkite.com", "Joseph Ivie")),
                html = "<p>Hello world!</p>",
            ))
        }
    }
}