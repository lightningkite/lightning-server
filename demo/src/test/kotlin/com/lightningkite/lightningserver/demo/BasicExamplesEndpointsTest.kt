package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasicExamplesEndpointsTest {

    @Test
    fun testRoot() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.root.test()
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Welcome to Lightning Server Demo!", response.body?.text())
        }
    }

    @Test
    fun testHelloWithName() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.helloWithName.test("Alice")
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Hello, Alice!", response.body?.text())
        }
    }

    @Test
    fun testHelloWithSpecialCharacters() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.helloWithName.test("João")
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Hello, João!", response.body?.text())
        }
    }

    @Test
    fun testGreetWithQueryNoParameters() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.greetWithQuery.test()
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Hello, Guest!", response.body?.text())
        }
    }

    @Test
    fun testGreetWithQueryNameOnly() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.greetWithQuery.test(
                queryParameters = QueryParameters(listOf("name" to "Bob"))
            )
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Hello, Bob!", response.body?.text())
        }
    }

    @Test
    fun testGreetWithQueryNameAndTitle() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.greetWithQuery.test(
                queryParameters = QueryParameters(
                    listOf(
                        "name" to "Smith",
                        "title" to "Dr"
                    )
                )
            )
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Hello, Dr Smith!", response.body?.text())
        }
    }

    @Test
    fun testEcho() = runBlocking {
        TestHelper.testServer {
            val testMessage = "test message"
            val response = Server.basic.echo.test(body = TypedData.text(testMessage, MediaType.Text.Plain))
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("You sent: $testMessage", response.body?.text())
        }
    }

    @Test
    fun testEchoEmptyBody() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.echo.test(body = TypedData.text("", MediaType.Text.Plain))
            assertEquals(HttpStatus.OK, response.status)
            assertTrue(response.body?.text()?.contains("You sent:") == true)
        }
    }

    @Test
    fun testCalculate() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.calculate.test(5, 3)
            assertEquals(HttpStatus.OK, response.status)
            val body = response.body?.text() ?: ""
            assertTrue(body.contains("5 + 3 = 8"))
            assertTrue(body.contains("5 - 3 = 2"))
            assertTrue(body.contains("5 × 3 = 15"))
        }
    }

    @Test
    fun testCalculateWithZero() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.calculate.test(10, 0)
            assertEquals(HttpStatus.OK, response.status)
            val body = response.body?.text() ?: ""
            assertTrue(body.contains("10 ÷ 0 = undefined"))
        }
    }

    @Test
    fun testCalculateNegativeNumbers() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.calculate.test(-5, 3)
            assertEquals(HttpStatus.OK, response.status)
            val body = response.body?.text() ?: ""
            assertTrue(body.contains("-5 + 3 = -2"))
        }
    }

    @Test
    fun testInfo() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.info.test()
            assertEquals(HttpStatus.OK, response.status)
            val body = response.body?.text() ?: ""
            assertTrue(body.contains("Lightning Server Demo"))
            assertTrue(body.contains("features"))
        }
    }

    @Test
    fun testSpecialCharsUrlEncoded() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.specialChars.test("hello/world")
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("You sent: hello/world", response.body?.text())
        }
    }

    @Test
    fun testSpecialCharsWithSpaces() = runBlocking {
        TestHelper.testServer {
            val response = Server.basic.specialChars.test("hello world")
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("You sent: hello world", response.body?.text())
        }
    }
}
