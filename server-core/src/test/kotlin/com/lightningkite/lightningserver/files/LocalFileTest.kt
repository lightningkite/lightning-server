package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import kotlin.test.assertContains

class LocalFileTest {
    @Test
    fun test() {
        TestSettings
        runBlocking {
            val system = LocalFileSystem(File("./build/test-files").absoluteFile, "local-file-test", null, JwtSigner())
            val testFile = system.root.resolve("test.txt")
            val message = "Hello world!"
            testFile.put(HttpContent.Text(message, ContentType.Text.Plain))
            assertEquals(message, testFile.get()!!.stream().reader().readText())
            assertNotNull(testFile.head())
            assertContains(testFile.parent!!.list()!!, testFile)
        }
    }
}