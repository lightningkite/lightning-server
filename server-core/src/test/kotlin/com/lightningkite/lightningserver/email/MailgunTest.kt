package com.lightningkite.lightningserver.email

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class MailgunTest {
    @Test
    fun testMailgun() {
        val credentials = File("local/test-mailgun.txt")
        if (!credentials.exists()) {
            println("No credentials to test with at ${credentials.absolutePath}")
            return
        }
        val client = EmailSettings(credentials.readText())()
        println((client as MailgunEmailClient).key)
        println((client as MailgunEmailClient).domain)
        runBlocking {
            client.send(Email("Test", to = listOf(EmailLabeledValue("joseph@lightningkite.com")), plainText = "Test Message"))
        }
    }
}