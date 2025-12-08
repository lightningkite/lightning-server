package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class ExpiringTest {
    object TestServer : ServerBuilder()

    @Test
    fun testNeverExpires() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val expiring = Expiring("test", expiresAt = null)
                assertFalse(expiring.expired)
            }
        }
    }

    @Test
    fun testNotYetExpired() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            val futureTime = Clock.System.now() + 1.hours
            runBlocking {
                val expiring = Expiring("test", expiresAt = futureTime)
                assertFalse(expiring.expired)
            }
        }
    }

    @Test
    fun testAlreadyExpired() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            val pastTime = Clock.System.now() - 1.hours
            runBlocking {
                val expiring = Expiring("test", expiresAt = pastTime)
                assertTrue(expiring.expired)
            }
        }
    }

    @Test
    fun testExpiredAtCurrentTime() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            val now = Clock.System.now()
            runBlocking {
                val expiring = Expiring("test", expiresAt = now)
                assertTrue(expiring.expired)
            }
        }
    }

    @Test
    fun testFactoryFunctionWithDuration() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val expiring = Expiring("test", expireAfter = 5.minutes)
                assertFalse(expiring.expired)
                assertEquals("test", expiring.value)
            }
        }
    }

    @Test
    fun testFactoryFunctionWithNullDuration() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val expiring = Expiring("test", expireAfter = null)
                assertFalse(expiring.expired)
                assertEquals(null, expiring.expiresAt)
            }
        }
    }

    @Test
    fun testValueAccessible() {
        val expiring = Expiring("test value", expiresAt = null)
        assertEquals("test value", expiring.value)
    }

    @Test
    fun testWithComplexType() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                data class User(val name: String, val age: Int)
                val user = User("Alice", 30)
                val expiring = Expiring(user, expiresAt = null)

                assertEquals(user, expiring.value)
                assertFalse(expiring.expired)
            }
        }
    }

    @Test
    fun testExpirationBoundary() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // Create expiring value that expires in 1 millisecond
                val expiring = Expiring("test", expireAfter = 1.milliseconds)

                // Should not be expired immediately
                assertFalse(expiring.expired)

                // Wait a bit
                kotlinx.coroutines.delay(10)

                // Should be expired now
                assertTrue(expiring.expired)
            }
        }
    }
}
