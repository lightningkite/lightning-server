package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.test.assertIs

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
            pin.generate("test")
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
            val expected = pin.generate("test")
            pin.assert("test", expected)
        }
    }

    @Test
    fun testChar() {
        runBlocking {
            val pin = PinHandler({ LocalCache }, "test", availableCharacters = ('A' .. 'Z').toList())
            pin.generate("test")
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
            val expected = pin.generate("test")
            pin.assert("test", expected)
        }
    }

    @Test
    fun testMixed() {
        runBlocking {
            val pin = PinHandler({ LocalCache }, "test", availableCharacters = ('a' .. 'z').toList() + ('A' .. 'Z').toList())
            pin.generate("test")
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
            val expected = pin.generate("test")
            pin.assert("test", expected)
        }
    }
}