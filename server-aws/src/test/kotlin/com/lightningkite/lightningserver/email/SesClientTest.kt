package com.lightningkite.lightningserver.email

import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.files.FilesSettings
import com.lightningkite.lightningserver.files.S3FileSystem
import com.lightningkite.lightningserver.files.TestSettings
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.time.Duration
import kotlin.test.assertContains

class SesClientTest {
    @Test
    fun test() {
        TestSettings
        runBlocking {
            val credentials = File("local/test-credentials-ses.txt")
            if(!credentials.exists()) {
                println("No credentials to test with at ${credentials.absolutePath}")
                return@runBlocking
            }
            SesClient
            val system = EmailSettings(credentials.readText(), fromEmail = "joseph@lightningkite.com")
            system().send(Email("Test", to = listOf(EmailLabeledValue("joseph@lightningkite.com")), plainText = "Test Message"))
        }
    }
}