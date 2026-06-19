package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Behavioral tests for the "Your First Endpoint" guide samples.
 *
 * Each test exercises a sample from FirstEndpointSamples.kt and asserts that
 * it does what the guide says it does.  If a sample is broken the test fails
 * and CI catches the rot before the docs go stale.
 */
class FirstEndpointTest {

    @Test
    fun `hello server returns greeting`() = runBlocking {
        HelloServer.test(settings = {}) {
            val response = HelloServer.root.test()
            assertEquals("Hello, Lightning Server!", response.body?.text())
        }
    }

    @Test
    fun `greet server interpolates path argument`() = runBlocking {
        GreetServer.test(settings = {}) {
            val response = GreetServer.greet.test("World")
            assertEquals("Hello, World!", response.body?.text())
        }
    }

    @Test
    fun `typed echo endpoint returns structured output`() = runBlocking {
        EchoServer.test(settings = {}) {
            // ApiHttpHandler.test() for noAuth endpoints accepts null auth and
            // returns the typed output directly — no JSON manipulation needed.
            val result = EchoServer.echo.test(null, EchoRequest("ping"))
            assertEquals("ping", result.echo)
            assertEquals(4, result.length)
        }
    }
}
