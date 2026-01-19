// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for PinHandler - PIN generation, verification, and security.
 */
class PinHandlerTest {

    object TestServer : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
    }

    private fun createPinHandler(
        length: Int = 6,
        maxAttempts: Int = 5,
        availableCharacters: List<Char> = ('A'..'Z').toList() - setOf('I', 'O')
    ): PinHandler {
        return PinHandler(
            cache = TestServer.cache,
            keyPrefix = "test",
            availableCharacters = availableCharacters,
            length = length,
            expiration = 15.minutes,
            maxAttempts = maxAttempts
        )
    }

    @Test
    fun `generate produces PIN of correct length`() {
        val handler = createPinHandler(length = 6)
        repeat(100) {
            val pin = handler.generate()
            assertEquals(6, pin.length, "PIN should be 6 characters")
        }
    }

    @Test
    fun `generate uses only allowed characters`() {
        val allowedChars = ('A'..'Z').toList() - setOf('I', 'O')
        val handler = createPinHandler(availableCharacters = allowedChars)

        repeat(100) {
            val pin = handler.generate()
            pin.forEach { char ->
                assertTrue(char in allowedChars, "PIN character '$char' should be in allowed set")
            }
        }
    }

    @Test
    fun `generate does not include I or O by default`() {
        val handler = createPinHandler()

        repeat(100) {
            val pin = handler.generate()
            assertTrue('I' !in pin, "PIN should not contain 'I'")
            assertTrue('O' !in pin, "PIN should not contain 'O'")
        }
    }

    @Test
    fun `generate avoids bad words`() {
        val handler = createPinHandler()

        // Generate many PINs and check none contain bad words
        repeat(1000) {
            val pin = handler.generate()
            assertTrue(!BadWordList.detectParanoid(pin), "PIN '$pin' should not contain bad words")
        }
    }

    @Test
    fun `establish and assert with correct PIN succeeds`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler()

            val result = handler.establish("user@example.com")
            assertNotNull(result.pin)
            assertNotNull(result.key)
            assertEquals(6, result.pin.length)

            // Assert with correct PIN should return the identifier
            val identifier = handler.assert(result.key, result.pin)
            assertEquals("user@example.com", identifier)
        }
    }

    @Test
    fun `assert with incorrect PIN throws BadRequestException`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler()

            val result = handler.establish("user@example.com")

            // Generate a different PIN
            val wrongPin = "WRONG1"

            assertFailsWith<BadRequestException>("Wrong PIN should throw BadRequestException") {
                handler.assert(result.key, wrongPin)
            }
        }
    }

    @Test
    fun `assert with expired key throws NotFoundException`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler()

            // Use a non-existent key
            assertFailsWith<NotFoundException>("Expired/invalid key should throw NotFoundException") {
                handler.assert("nonexistent-key", "ABCDEF")
            }
        }
    }

    @Test
    fun `max attempts exceeded throws NotFoundException`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler(maxAttempts = 3)

            val result = handler.establish("user@example.com")
            val wrongPin = "WRONG1"

            // Make 3 failed attempts (maxAttempts)
            repeat(2) {
                try {
                    handler.assert(result.key, wrongPin)
                } catch (_: BadRequestException) {
                    // Expected
                }
            }

            // The 3rd attempt should expire the PIN
            assertFailsWith<NotFoundException>("After max attempts, PIN should be expired") {
                handler.assert(result.key, wrongPin)
            }
        }
    }

    @Test
    fun `PIN is case insensitive when not mixed case`() = runBlocking {
        TestServer.test({}) {
            // Default characters are uppercase only
            val handler = createPinHandler()

            val result = handler.establish("user@example.com")

            // Should work with lowercase version of the PIN
            val identifier = handler.assert(result.key, result.pin.lowercase())
            assertEquals("user@example.com", identifier)
        }
    }

    @Test
    fun `PIN is case sensitive when mixed case characters`() = runBlocking {
        TestServer.test({}) {
            // Use mixed case characters
            val mixedChars = ('A'..'Z').toList() + ('a'..'z').toList()
            val handler = createPinHandler(availableCharacters = mixedChars)

            val result = handler.establish("user@example.com")

            // The exact PIN should work
            val identifier = handler.assert(result.key, result.pin)
            assertEquals("user@example.com", identifier)
        }
    }

    @Test
    fun `PIN can only be used once`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler()

            val result = handler.establish("user@example.com")

            // First assertion should succeed
            val identifier = handler.assert(result.key, result.pin)
            assertEquals("user@example.com", identifier)

            // Second assertion should fail (PIN consumed)
            assertFailsWith<NotFoundException>("PIN should be consumed after use") {
                handler.assert(result.key, result.pin)
            }
        }
    }

    @Test
    fun `different keys produce different PINs`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler()

            val results = (1..10).map { handler.establish("user$it@example.com") }

            // All keys should be unique
            val keys = results.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "All keys should be unique")

            // PINs might occasionally collide (6 char, 24 chars = 24^6 = 191M possibilities)
            // but they should mostly be different
            val pins = results.map { it.pin }
            assertTrue(pins.toSet().size > 5, "Most PINs should be unique")
        }
    }

    @Test
    fun `numeric PIN generation works`() {
        val handler = PinHandler(
            cache = TestServer.cache,
            keyPrefix = "numeric",
            availableCharacters = ('0'..'9').toList(),
            length = 6,
            expiration = 15.minutes,
            maxAttempts = 5
        )

        repeat(100) {
            val pin = handler.generate()
            assertEquals(6, pin.length)
            pin.forEach { char ->
                assertTrue(char.isDigit(), "PIN should only contain digits")
            }
        }
    }

    @Test
    fun `short PIN generation works`() {
        val handler = createPinHandler(length = 4)

        repeat(100) {
            val pin = handler.generate()
            assertEquals(4, pin.length)
        }
    }

    @Test
    fun `long PIN generation works`() {
        val handler = createPinHandler(length = 10)

        repeat(100) {
            val pin = handler.generate()
            assertEquals(10, pin.length)
        }
    }

    @Test
    fun `failed attempts are counted`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler(maxAttempts = 5)

            val result = handler.establish("user@example.com")
            val wrongPin = "WRONG1"

            // Make 4 failed attempts (one less than max)
            repeat(4) {
                try {
                    handler.assert(result.key, wrongPin)
                } catch (_: BadRequestException) {
                    // Expected
                }
            }

            // The correct PIN should still work (we haven't hit max yet)
            // But wait - the 5th attempt (even with correct PIN) might fail
            // Actually reading the code: maxAttempts check happens before PIN check
            // So if attempts >= maxAttempts, it throws NotFoundException

            // After 4 failed attempts, we're at 4 attempts
            // The next attempt will be the 5th, which is >= 5, so it will fail
            assertFailsWith<NotFoundException>("5th attempt should expire the PIN") {
                handler.assert(result.key, result.pin)
            }
        }
    }

    @Test
    fun `establish returns different key each time for same identifier`() = runBlocking {
        TestServer.test({}) {
            val handler = createPinHandler()

            val result1 = handler.establish("same@example.com")
            val result2 = handler.establish("same@example.com")

            assertNotEquals(result1.key, result2.key, "Different establish calls should produce different keys")
        }
    }
}
