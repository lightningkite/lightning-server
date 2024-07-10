package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.now
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

abstract class FileSystemTests {
    abstract val system: FileSystem?

    @Test
    fun testHealth() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runBlocking {
            system.healthCheck()
        }
    }

    @Test
    fun testWriteAndRead() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            assertEquals(message, testFile.get()!!.stream().reader().readText())
        }
    }

    @Test
    fun testInfo() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            val beforeModify = now().minus(120.seconds)
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            val info = testFile.head()
            assertNotNull(info)
            assertEquals(ContentType.Text.Plain, info!!.type)
            assertTrue(info.size > 0L)
            assertTrue(info.lastModified > beforeModify)

            // Testing with sub folders.
            val secondFile = system.root.resolve("test/secondTest.txt")
            val secondMessage = "Hello Second world!"
            val secondBeforeModify = now().minus(120.seconds)
            secondFile.put(HttpContent.Text(secondMessage, ContentType.Text.Plain))
            val secondInfo = secondFile.head()
            assertNotNull(secondInfo)
            assertEquals(ContentType.Text.Plain, secondInfo!!.type)
            assertTrue(secondInfo.size > 0L)
            assertTrue(secondInfo.lastModified > secondBeforeModify)
        }
    }

    @Test
    fun testList() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runBlocking {
            withTimeout(10_000L) {
                val testFile = system.root.resolve("test.txt")
                val message = "Hello world!"
                testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
                val testFileNotIncluded = system.root.resolve("doNotInclude/test.txt")
                testFileNotIncluded.put(HttpContent.Text(message, ContentType.Text.Plain))
                assertContains(testFile.parent!!.list()!!.also { println(it) }, testFile)
                assertFalse(testFileNotIncluded in testFile.parent!!.list()!!)
                testFile.get()!!.stream().readAllBytes()
            }
        }
    }

    @Test
    open fun testSignedUrlAccess() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            assert(testFile.signedUrl.startsWith(testFile.url))
            println("testFile.signedUrl: ${testFile.signedUrl}")
            assert(client.get(testFile.signedUrl).status.isSuccess())
        }
    }

    open fun uploadHeaders(builder: HttpRequestBuilder) {}

    @Test
    open fun testSignedUpload() {
        val system = system ?: run {
            println("Could not test because the file system isn't supported here.")
            return
        }
        runBlocking {
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            assert(client.put(testFile.uploadUrl(1.hours)) {
                uploadHeaders(this)
                setBody(TextContent(message, io.ktor.http.ContentType.Text.Plain))
            }.status.isSuccess())
            println(Serialization.json.encodeToString(ServerFile(testFile.url)))
        }
    }
}
