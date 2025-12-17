package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Tests for secure password hashing using PBKDF2-HMAC-SHA512.
 */
class HashTests {
    /**
     * Tests that password hashing is consistent - the same password
     * with the same salt produces the same hash.
     */
    @Test
    fun hashingIsConsistent(): Unit = runBlocking {
        repeat(10) {
            println("Test #${it + 1}")
            val data = Base64.encode(Random.nextBytes(Random.nextInt(8, 64)))
            val secret = data.secureHash()
            assert(data.checkAgainstHash(secret)) {
                """
                    Data: $data
                    Hashed: $secret
                """.trimIndent()
            }
        }
    }

    /**
     * Performance comparison between Kotlin cryptography library
     * and Java javax.crypto implementations.
     */
    @Test fun hashPerformance(): Unit = runBlocking {
        measureTime { repeat(5) { "asdfa".secureHash() } }.also { println("Kotlin: $it") }
        measureTime { repeat(5) { "asdfa".secureHashJava() } }.also { println("Java: $it") }
        val hash = "asdfa".secureHash()
        measureTime { repeat(5) { "asdfa".checkAgainstHash(hash) } }.also { println("Kotlin: $it") }
        measureTime { repeat(5) { "asdfa".checkAgainstHashJava(hash) } }.also { println("Java: $it") }
    }

    /**
     * Tests that each hash uses a unique random salt, so the same
     * password produces different hashes on each call.
     */
    @Test
    fun hashingIsSalted(): Unit = runBlocking {
        val data = "Hello World"
        val h1 = data.secureHash()
        val h2 = data.secureHash()
        assertNotEquals(h1, h2)
    }

    /**
     * Tests cross-compatibility between Kotlin and Java implementations -
     * both should be able to verify hashes created by the other.
     */
    @Test
    fun hashingIsConsistentWithOld(): Unit = runBlocking {
        val data = "Hello World"
        val h1 = data.secureHash()
        val h2 = data.secureHashJava()

        // Internal consistency first
        assert(data.checkAgainstHash(h1))
        assert(data.checkAgainstHashJava(h2))

        // External consistency second
        assert(data.checkAgainstHashJava(h1))
        assert(data.checkAgainstHash(h2))
    }

    // ========== Fast Hash Tests ==========

    /**
     * Tests that fast hashing produces correct SHA256 format and verifies correctly.
     */
    @Test
    fun fastHashTest(): Unit = runBlocking {
        val hash = "asdf".fastHash()
        println("SHA256 Hash is $hash")
        assertTrue(hash.startsWith("SHA256."), "Hash should start with SHA256 prefix")
        measureTime {
            assertTrue("asdf".checkAgainstHash(hash))
        }.also { println("SHA256 verify correct: $it") }
        measureTime {
            assertFalse("asdff".checkAgainstHash(hash))
        }.also { println("SHA256 verify incorrect: $it") }
    }

    /**
     * Tests that fast hash is significantly faster than secure hash.
     */
    @Test
    fun fastHashPerformance(): Unit = runBlocking {
        // Fast hash should be significantly faster than secure hash
        val fastTime = measureTime {
            repeat(100) {
                val hash = "test$it".fastHash()
                "test$it".checkAgainstHash(hash)
            }
        }
        println("100 fast hash+verify operations: $fastTime")
        assertTrue(fastTime.inWholeMilliseconds < 1000, "Fast hash should complete 100 operations in under 1 second")
    }

    /**
     * Tests that both hash types verify correctly against their own format.
     */
    @Test
    fun crossHashVerification(): Unit = runBlocking {
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        // Each should validate against its own type
        assertTrue("asdf".checkAgainstHash(secureHash), "Should verify against secure hash")
        assertTrue("asdf".checkAgainstHash(fastHash), "Should verify against fast hash")

        // Wrong password should fail for both
        assertFalse("wrong".checkAgainstHash(secureHash), "Wrong password should fail secure hash")
        assertFalse("wrong".checkAgainstHash(fastHash), "Wrong password should fail fast hash")
    }

    /**
     * Tests empty string handling for both hash types.
     */
    @Test
    fun emptyStringHandling(): Unit = runBlocking {
        assertEquals("", "".secureHash(), "Empty input should return empty hash for secureHash")
        assertEquals("", "".fastHash(), "Empty input should return empty hash for fastHash")
        assertFalse("anything".checkAgainstHash(""), "Empty hash should never validate")
    }

    /**
     * Tests the isSlowHash detection function.
     */
    @Test
    fun isSlowHashTest(): Unit = runBlocking {
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        assertTrue(secureHash.isSlowHash(), "PBKDF2 hash should be detected as slow")
        assertFalse(fastHash.isSlowHash(), "SHA256 hash should not be detected as slow")
    }

    /**
     * Tests that already-hashed values are returned unchanged (idempotent).
     */
    @Test
    fun idempotentHashTest(): Unit = runBlocking {
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        assertEquals(secureHash, secureHash.secureHash(), "Already hashed PBKDF2 should return unchanged")
        assertEquals(fastHash, fastHash.fastHash(), "Already hashed SHA256 should return unchanged")
    }

    /**
     * Tests that unknown prefixes fail validation.
     */
    @Test
    fun unknownPrefixFails(): Unit = runBlocking {
        assertFalse("asdf".checkAgainstHash("UNKNOWN.salt.hash"), "Unknown prefix should fail validation")
        assertFalse("asdf".checkAgainstHash("randomgarbage"), "Random garbage should fail validation")
    }

    /**
     * Tests that different salts produce different hashes for the same input.
     */
    @Test
    fun differentSaltsProduceDifferentHashes() {
        val hash1 = "asdf".fastHash()
        val hash2 = "asdf".fastHash()
        assertNotEquals(hash1, hash2, "Same input should produce different hashes due to random salt")
        // But both should still validate
        runBlocking {
            assertTrue("asdf".checkAgainstHash(hash1), "First hash should validate")
            assertTrue("asdf".checkAgainstHash(hash2), "Second hash should validate")
        }
    }

    /**
     * Tests that secureHash produces correct PBKDF2 format.
     */
    @Test
    fun secureHashFormat(): Unit = runBlocking {
        val hash = "asdf".secureHash()
        println("PBKDF2 Hash is $hash")
        assertTrue(hash.startsWith("PBKDF2WithHmacSHA512."), "Hash should start with PBKDF2 prefix")
        assertTrue("asdf".checkAgainstHash(hash), "Should verify correctly")
        assertFalse("wrong".checkAgainstHash(hash), "Wrong password should fail")
    }
}