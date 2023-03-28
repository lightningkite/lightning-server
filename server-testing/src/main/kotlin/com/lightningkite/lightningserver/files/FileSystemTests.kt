package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains

abstract class FileSystemTests {
    abstract val system: FileSystem?

    @Test
    fun testHealth() {
        val system = system ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            system.healthCheck()
        }
    }

    @Test
    fun testWriteAndRead() {
        val system = system ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.write(HttpContent.Text(message, ContentType.Text.Plain))
            assertEquals(message, testFile.read().reader().readText())
        }
    }

    @Test
    fun testInfo() {
        val system = system ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            val beforeModify = Instant.now().minusSeconds(120L)
            testFile.write(HttpContent.Text(message, ContentType.Text.Plain))
            val info = testFile.info()
            assertNotNull(info)
            assertEquals(ContentType.Text.Plain, info!!.type)
            assertTrue(info.size > 0L)
            assertTrue(info.lastModified > beforeModify)
        }
    }

    @Test
    fun testList() {
        val system = system ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            withTimeout(10_000L) {
                val testFile = system.root.resolve("test.txt")
                val message = "Hello world!"
                testFile.write(HttpContent.Text(message, ContentType.Text.Plain))
                val testFileNotIncluded = system.root.resolve("doNotInclude/test.txt")
                testFileNotIncluded.write(HttpContent.Text(message, ContentType.Text.Plain))
                assertContains(testFile.parent!!.list()!!.also { println(it) }, testFile)
                assertFalse(testFileNotIncluded in testFile.parent!!.list()!!)
            }
        }
    }

    @Test
    open fun testSignedUrlAccess() {
        val system = system ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.write(HttpContent.Text(message, ContentType.Text.Plain))
            assert(testFile.signedUrl.startsWith(testFile.url))
            println("testFile.signedUrl: ${testFile.signedUrl}")
            assert(client.get(testFile.signedUrl).status.isSuccess())
        }
    }

    open fun uploadHeaders(builder: HttpRequestBuilder) {}

    @Test
    open fun testSignedUpload() {
        val system = system ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            assert(client.put(testFile.uploadUrl(Duration.ofHours(1))) {
                uploadHeaders(this)
                setBody(TextContent(message, io.ktor.http.ContentType.Text.Plain))
            }.status.isSuccess())
            println(Serialization.json.encodeToString(ServerFile(testFile.url)))
        }
    }
}
