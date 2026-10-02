package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.auth.old.PinHandler
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.test.assertIs


@Suppress("Deprecation")
class PinHandlerTest {

    private inline fun <reified T : Exception> assertException(action: () -> Unit, verify: (T) -> Boolean = { true }) {
        try {
            action()
            fail()
        } catch (e: Exception) {
            assertIs<T>(e)
            assertTrue(verify(e))
        }
    }

    @Test
    fun test() {
        runBlocking {
            val pin = PinHandler({ LocalCache }, "test")
            pin.establish("test")
            repeat(pin.maxAttempts - 1) {
                assertException<BadRequestException>(
                    action = { pin.assert("test", "wrong") },
                    verify = { it.detail == "pin-incorrect" }
                )
            }
            assertException<NotFoundException>(
                action = { pin.assert("test", "wrong") },
                verify = { it.detail == "pin-expired" }
            )
            val expected = pin.establish("test")
            pin.assert("test", expected)
        }
    }

    @Test
    fun testChar() {
        runBlocking {
            val pin = PinHandler({ LocalCache }, "test", availableCharacters = ('A' .. 'Z').toList())
            pin.establish("test")
            repeat(pin.maxAttempts - 1) {
                assertException<BadRequestException>(
                    action = { pin.assert("test", "wrong") },
                    verify = { it.detail == "pin-incorrect" }
                )
            }
            assertException<NotFoundException>(
                action = { pin.assert("test", "wrong") },
                verify = { it.detail == "pin-expired" }
            )
            val expected = pin.establish("test")
            pin.assert("test", expected)
        }
    }

    @Test
    fun testMixed() {
        runBlocking {
            val pin = PinHandler({ LocalCache }, "test", availableCharacters = ('a' .. 'z').toList() + ('A' .. 'Z').toList())
            pin.establish("test")
            repeat(pin.maxAttempts - 1) {
                assertException<BadRequestException>(
                    action = { pin.assert("test", "wrong") },
                    verify = { it.detail == "pin-incorrect" }
                )
            }
            assertException<NotFoundException>(
                action = { pin.assert("test", "wrong") },
                verify = { it.detail == "pin-expired" }
            )
            val expected = pin.establish("test")
            pin.assert("test", expected)
        }
    }

    @Test
    fun concurrentGuessesShareTheAttemptLimit(): Unit = runBlocking {
        // Yielding on every read lets all the guesses read the counter before any of them writes it.
        val inner = LocalCache()
        val slowReads = object : Cache by inner {
            override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
                yield()
                return inner.get(key, serializer)
            }
        }
        val pin = PinHandler({ slowReads }, "race")
        pin.establish("someone")
        val results = (1..20).map {
            async {
                try {
                    pin.assert("someone", "wrong!")
                    "accepted"
                } catch (e: HttpStatusException) {
                    e.detail
                }
            }
        }.awaitAll()
        assertTrue(results.toString(), results.count { it == "pin-incorrect" } <= pin.maxAttempts - 1)
    }
}
