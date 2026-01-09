package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecretBasisThreadSafetyTest {

    @Test
    fun `key() should only decode once with concurrent access`() = runBlocking {
        val basis = SecretBasis()

        // Launch 100 concurrent coroutines trying to access the key
        val keys = (1..100).map {
            async(Dispatchers.Default) {
                basis.key()
            }
        }.awaitAll()

        // All keys should be the exact same instance (object identity)
        assertEquals(100, keys.size)
        val firstKey = keys.first()
        keys.forEach { key ->
            assertSame(firstKey, key, "All keys should be the same instance")
        }
    }

    @Test
    fun `keyBlocking() should only decode once with concurrent access`() = runBlocking {
        val basis = SecretBasis()

        // Launch 100 concurrent threads trying to access the key
        val keys = (1..100).map {
            async(Dispatchers.Default) {
                basis.keyBlocking()
            }
        }.awaitAll()

        // All keys should be the exact same instance (object identity)
        assertEquals(100, keys.size)
        val firstKey = keys.first()
        keys.forEach { key ->
            assertSame(firstKey, key, "All keys should be the same instance")
        }
    }

    @Test
    fun `mixed key() and keyBlocking() calls should return same instance`() = runBlocking {
        val basis = SecretBasis()

        // Mix of async and blocking calls
        val results = coroutineScope {
            val asyncKeys = (1..50).map {
                async(Dispatchers.Default) {
                    basis.key()
                }
            }
            val blockingKeys = (1..50).map {
                async(Dispatchers.Default) {
                    basis.keyBlocking()
                }
            }

            (asyncKeys + blockingKeys).awaitAll()
        }

        // All keys should be the exact same instance
        assertEquals(100, results.size)
        val firstKey = results.first()
        results.forEach { key ->
            assertSame(firstKey, key, "All keys should be the same instance")
        }
    }

    @Test
    fun `derive() should work correctly with concurrent access`() = runBlocking {
        val basis = SecretBasis()

        // Launch 100 concurrent coroutines deriving keys
        val derived = (1..100).map {
            async(Dispatchers.Default) {
                basis.derive("variant-$it")
            }
        }.awaitAll()

        // All derived keys should have the expected length (64 bytes for SHA-512)
        assertEquals(100, derived.size)
        derived.forEach { key ->
            assertEquals(64, key.size, "Derived key should be 64 bytes")
        }

        // Same variant should produce same derived key
        val sameVariant1 = basis.derive("test-variant")
        val sameVariant2 = basis.derive("test-variant")
        assertEquals(sameVariant1.toList(), sameVariant2.toList())
    }

    @Test
    fun `deriveBlocking() should work correctly with concurrent access`() = runBlocking {
        val basis = SecretBasis()

        // Launch 100 concurrent threads deriving keys
        val derived = (1..100).map {
            async(Dispatchers.Default) {
                basis.deriveBlocking("variant-$it")
            }
        }.awaitAll()

        // All derived keys should have the expected length
        assertEquals(100, derived.size)
        derived.forEach { key ->
            assertEquals(64, key.size, "Derived key should be 64 bytes")
        }

        // Same variant should produce same derived key
        val sameVariant1 = basis.deriveBlocking("test-variant")
        val sameVariant2 = basis.deriveBlocking("test-variant")
        assertEquals(sameVariant1.toList(), sameVariant2.toList())
    }

    /**
     * Spam test for key() to detect race conditions.
     *
     * Uses CountDownLatch to maximize contention by ensuring all coroutines
     * start at exactly the same time. Runs multiple rounds with fresh instances
     * to increase the chance of hitting race conditions.
     */
    @Test
    fun `key() spam test with maximum contention`() = runBlocking {
        val threadCount = 1000
        val rounds = 10

        repeat(rounds) { round ->
            val basis = SecretBasis() // Fresh instance each round
            val latch = CountDownLatch(1)
            val readyCount = AtomicInteger(0)

            val keys = (1..threadCount).map {
                async(Dispatchers.Default) {
                    // Signal ready and wait for all threads to be ready
                    readyCount.incrementAndGet()
                    @Suppress("BlockingMethodInNonBlockingContext")
                    latch.await()

                    basis.key()
                }
            }

            // Wait for all coroutines to be ready, then release them all at once
            while (readyCount.get() < threadCount) {
                yield()
            }
            latch.countDown()

            val results = keys.awaitAll()

            // All keys should be the same instance
            val firstKey = results.first()
            val allSame = results.all { it === firstKey }
            assertTrue(allSame, "Round $round: Expected all ${results.size} keys to be the same instance, but found ${results.distinct().size} distinct instances")
        }
    }

    /**
     * Spam test for keyBlocking() to detect race conditions.
     *
     * Uses real threads with CountDownLatch for maximum contention.
     */
    @Test
    fun `keyBlocking() spam test with maximum contention`() {
        val threadCount = 1000
        val rounds = 10

        repeat(rounds) { round ->
            val basis = SecretBasis()
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val results = mutableListOf<Any>()
            val resultsLock = Any()

            val futures = (1..threadCount).map {
                executor.submit {
                    latch.await()
                    val key = basis.keyBlocking()
                    synchronized(resultsLock) {
                        results.add(key)
                    }
                }
            }

            // Small delay to let threads start and block on latch
            Thread.sleep(100)
            latch.countDown()

            futures.forEach { it.get() }
            executor.shutdown()

            // All keys should be the same instance
            val firstKey = results.first()
            val allSame = results.all { it === firstKey }
            assertTrue(allSame, "Round $round: Expected all ${results.size} keys to be the same instance, but found ${results.distinct().size} distinct instances")
        }
    }

    /**
     * Spam test mixing key() and keyBlocking() to detect cross-API race conditions.
     */
    @Test
    fun `mixed key() and keyBlocking() spam test`() = runBlocking {
        val threadCount = 500
        val rounds = 10

        repeat(rounds) { round ->
            val basis = SecretBasis()
            val latch = CountDownLatch(1)
            val readyCount = AtomicInteger(0)

            // Half use key(), half use keyBlocking()
            val asyncKeys = (1..threadCount).map {
                async(Dispatchers.Default) {
                    readyCount.incrementAndGet()
                    @Suppress("BlockingMethodInNonBlockingContext")
                    latch.await()
                    basis.key()
                }
            }
            val blockingKeys = (1..threadCount).map {
                async(Dispatchers.Default) {
                    readyCount.incrementAndGet()
                    @Suppress("BlockingMethodInNonBlockingContext")
                    latch.await()
                    basis.keyBlocking()
                }
            }

            // Wait for all to be ready
            while (readyCount.get() < threadCount * 2) {
                yield()
            }
            latch.countDown()

            val results = (asyncKeys + blockingKeys).awaitAll()

            // All keys should be the same instance
            val firstKey = results.first()
            val distinctCount = results.distinct().size
            assertTrue(
                distinctCount == 1,
                "Round $round: Expected 1 distinct key instance, but found $distinctCount"
            )
        }
    }
}
