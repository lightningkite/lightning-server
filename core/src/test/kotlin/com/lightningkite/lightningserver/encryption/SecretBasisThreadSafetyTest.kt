package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}
