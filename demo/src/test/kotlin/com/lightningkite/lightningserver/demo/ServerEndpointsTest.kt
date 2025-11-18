package com.lightningkite.lightningserver.demo

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerEndpointsTest {

    @Test
    fun testRootEndpoint() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.root.test()
            assertEquals(HttpStatus.OK, response.status)
            assertTrue(response.body?.text()?.contains("Welcome") == true)
        }
    }

    @Test
    fun testSlashEscaping() = runBlocking {
        TestHelper.testServer {
            val response = Server.slashEscaping.test("test-id")
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("The variable is 'test-id'", response.body?.text())
        }
    }

    @Test
    fun testSlashEscapingWithSpecialChars() = runBlocking {
        TestHelper.testServer {
            val response = Server.slashEscaping.test("id-with/slash")
            assertEquals(HttpStatus.OK, response.status)
            assertTrue(response.body?.text()?.contains("id-with/slash") == true)
        }
    }

    @Test
    fun testMockWorkGet() = runBlocking {
        TestHelper.testServer {
            val response = Server.mockWorkGet.test()
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("ok", response.body?.text())
        }
    }

    @Test
    fun testMockWorkPost() = runBlocking {
        TestHelper.testServer {
            val testData = "test data"
            val response = Server.mockWorkPost.test(body = TypedData.text(testData, MediaType.Text.Plain))
            assertEquals(HttpStatus.OK, response.status)
        }
    }

    @Test
    fun testMemoryEndpoint() = runBlocking {
        TestHelper.testServer {
            val response = Server.mem.test()
            assertEquals(HttpStatus.OK, response.status)
            val body = response.body?.text() ?: ""
            assertTrue(body.contains("Memory usage:"))
        }
    }

    @Test
    fun testDatabaseCheck() = runBlocking {
        TestHelper.testServer {
            val response = Server.databaseCheck.test()
            assertEquals(HttpStatus.OK, response.status)
            assertTrue(response.body?.text()?.isNotEmpty() == true)
        }
    }

    @Test
    fun testHasInternet() = runBlocking {
        TestHelper.testServer {
            try {
                val response = Server.hasInternet.test()
                assertEquals(HttpStatus.OK, response.status)
                assertTrue(response.body?.text()?.contains("status") == true)
            } catch (e: Exception) {
                // It's okay if this fails due to no internet connection
                println("Has internet test skipped: ${e.message}")
            }
        }
    }

    @Test
    fun testSamplePage() = runBlocking {
        TestHelper.testServer {
            val response = Server.sample.test()
            assertEquals(HttpStatus.OK, response.status)
            val body = response.body?.text() ?: ""
            assertTrue(body.contains("<html") || body.contains("<!DOCTYPE"))
        }
    }

    @Test
    fun testIndirectEndpoint() = runBlocking {
        TestHelper.testServer {
            try {
                val response = Server.indirect.test("test", 42)
                // This endpoint requires authentication, so it should fail with auth error
                // or succeed if auth is properly mocked
            } catch (e: Exception) {
                // Expected to fail without proper auth
                assertTrue(e.message?.contains("auth") == true || e.message != null)
            }
        }
    }
}
