// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.withClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Tests for [constrainAttemptRate]'s exponential backoff behavior.
 *
 * The RAM [Cache] evaluates TTL expiry against [Clock.default], which reads the coroutine-context
 * clock set by [withClock], while `now()` reads the [ServerRuntime] clock passed to `test`. Both are
 * driven by the same [MutableClock] here so advancing time expires cache entries deterministically.
 */
class ConstrainAttemptRateTest {

    object TestServer : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
    }

    /** A clock whose [now] can be moved forward to simulate elapsed time in tests. */
    private class MutableClock(var instant: Instant = Instant.parse("2024-01-01T00:00:00Z")) : Clock {
        override fun now(): Instant = instant
        fun advance(by: Duration) {
            instant += by
        }
    }

    /** Runs [failures] failing attempts (each below the block threshold), asserting each rethrows. */
    context(server: ServerRuntime)
    private suspend fun Cache.failN(key: String, count: Int, blocked: Duration, failures: Int) {
        repeat(failures) {
            assertFailsWith<IllegalStateException> {
                constrainAttemptRate(key, count = count, blocked = blocked) {
                    throw IllegalStateException("simulated failure")
                }
            }
        }
    }

    /**
     * Drives the counter to the limit and returns the block message thrown by the next (blocked)
     * attempt. Assumes the counter starts empty for [key].
     */
    context(server: ServerRuntime)
    private suspend fun Cache.hitLimit(key: String, count: Int, blocked: Duration): String {
        failN(key, count, blocked, count)
        val ex = assertFailsWith<BadRequestException> {
            constrainAttemptRate(key, count = count, blocked = blocked) { /* not reached */ }
        }
        return ex.message
    }

    @Test
    fun `first block matches configured blocked duration`() = runBlocking {
        val clock = MutableClock()
        TestServer.test(settings = {}, clock = { clock }) {
            withClock(clock) {
                val message = TestServer.cache().hitLimit("first-block", count = 3, blocked = 10.minutes)
                assertTrue(message.contains("10 minutes"), "Expected first block of 10 minutes, got: $message")
            }
        }
    }

    @Test
    fun `repeat offender across block windows gets exponentially longer block`() = runBlocking {
        val clock = MutableClock()
        TestServer.test(settings = {}, clock = { clock }) {
            withClock(clock) {
                val cache = TestServer.cache()
                val key = "popcorn"

                // Round 1: first offense -> base block (level 0).
                val first = cache.hitLimit(key, count = 3, blocked = 10.minutes)
                assertTrue(first.contains("10 minutes"), "Round 1 should be 10 minutes, got: $first")

                // Let the block window (and attempt counter) lapse, but not the long-lived strike level.
                clock.advance(11.minutes)

                // Round 2: returning attacker gets a fresh batch of attempts, but the remembered
                // strike level doubles the block -> 20 minutes. This is the popcorn defense.
                val second = cache.hitLimit(key, count = 3, blocked = 10.minutes)
                assertTrue(second.contains("20 minutes"), "Round 2 should be 20 minutes (exponential), got: $second")
            }
        }
    }

    @Test
    fun `successful action resets strike level so later first offense is short again`() = runBlocking {
        val clock = MutableClock()
        TestServer.test(settings = {}, clock = { clock }) {
            withClock(clock) {
                val cache = TestServer.cache()
                val key = "reset"

                // Escalate to level 1.
                assertTrue(cache.hitLimit(key, count = 3, blocked = 10.minutes).contains("10 minutes"))
                clock.advance(11.minutes)
                assertTrue(cache.hitLimit(key, count = 3, blocked = 10.minutes).contains("20 minutes"))

                // Let the block lapse, then a legitimate success clears the strike level.
                clock.advance(21.minutes)
                val ok = cache.constrainAttemptRate(key, count = 3, blocked = 10.minutes) { "success" }
                assertEquals("success", ok)

                // A later first offense is short again because the level was reset.
                clock.advance(1.minutes)
                val afterReset = cache.hitLimit(key, count = 3, blocked = 10.minutes)
                assertTrue(afterReset.contains("10 minutes"), "After success the block should reset to 10 minutes, got: $afterReset")
            }
        }
    }

    @Test
    fun `block is capped at maxBlocked`() = runBlocking {
        val clock = MutableClock()
        TestServer.test(settings = {}, clock = { clock }) {
            withClock(clock) {
                val cache = TestServer.cache()
                val key = "cap"
                val count = 1
                val blocked = 10.minutes
                val maxBlocked = 25.minutes

                // First failure fills the single allowed attempt.
                assertFailsWith<IllegalStateException> {
                    cache.constrainAttemptRate(key, count = count, blocked = blocked, maxBlocked = maxBlocked) {
                        throw IllegalStateException("simulated failure")
                    }
                }

                // Hammer past the limit; the block escalates 10 -> 20 -> capped at 25 (not 40) minutes.
                val messages = (0 until 3).map {
                    assertFailsWith<BadRequestException> {
                        cache.constrainAttemptRate(key, count = count, blocked = blocked, maxBlocked = maxBlocked) { }
                    }.message
                }
                assertTrue(messages[0].contains("10 minutes"), "First block should be 10 minutes, got: ${messages[0]}")
                assertTrue(messages[1].contains("20 minutes"), "Second block should be 20 minutes, got: ${messages[1]}")
                assertTrue(messages[2].contains("25 minutes"), "Third block should be capped at 25 minutes, got: ${messages[2]}")
            }
        }
    }
}
