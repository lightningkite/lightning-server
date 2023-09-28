package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import kotlin.time.Duration
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.hours

class LocalFileTest {
    @Test
    fun test() {
        TestSettings
        runBlocking {
            val system = LocalFileSystem(File("./build/test-files").absoluteFile, "local-file-test", null, signer = SecureHasher.HS256(Random.nextBytes(16)))
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            assertEquals(message, testFile.get()!!.stream().reader().readText())
            assertNotNull(testFile.head())
            assertContains(testFile.parent!!.list()!!, testFile)
        }
    }

    @Test fun signTest() {
        TestSettings
        runBlocking {
            val system = LocalFileSystem(File("./build/test-files").absoluteFile, "local-file-test", 1.hours, signer = SecureHasher.HS256(Random.nextBytes(16)))
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            testFile.checkSignature(testFile.signedUrl.substringAfter('?'))
            println(testFile.signedUrl)
        }
    }
}