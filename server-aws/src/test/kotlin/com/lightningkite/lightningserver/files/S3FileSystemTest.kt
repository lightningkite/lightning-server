package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import software.amazon.awssdk.regions.Region
import java.io.File
import java.time.Duration
import kotlin.test.assertContains

class S3FileSystemTest {
    @Test fun test() {
        TestSettings
        runBlocking {
            val credentials = File("build/test-credentials.txt")
            if(!credentials.exists()) {
                println("No credentials to test with at ${credentials.absolutePath}")
                return@runBlocking
            }
            S3FileSystem
            val system = FilesSettings(credentials.readText(), signedUrlExpiration = Duration.ofSeconds(100))()
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.write(HttpContent.Text(message, ContentType.Text.Plain))
            assertEquals(message, testFile.read().reader().readText())
            assertNotNull(testFile.info())
            assertContains(testFile.parent!!.list()!!.also { println(it) }, testFile)
            assert(testFile.signedUrl.startsWith(testFile.url))
            assert(client.get(testFile.signedUrl).status.isSuccess())
            assert(client.put(testFile.uploadUrl(Duration.ofHours(1))) { setBody(TextContent(message, io.ktor.http.ContentType.Text.Plain)) }.status.isSuccess())
        }
    }
}