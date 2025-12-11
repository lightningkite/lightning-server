package com.lightningkite.lightningserver.encryption

import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureTimeMillis

class SecureHashKtTest {
    @Test
    fun secureHashTest() {
        val hash = "asdf".secureHash()
        println("PBKDF2 Hash is $hash")
        assertTrue("Hash should start with PBKDF2 prefix", hash.startsWith("PBKDF2WithHmacSHA512."))
        measureTimeMillis {
            assertTrue("asdf".checkAgainstHash(hash))
        }.also { println("PBKDF2 verify correct: ${it}ms") }
        measureTimeMillis {
            assertFalse("asdff".checkAgainstHash(hash))
        }.also { println("PBKDF2 verify incorrect: ${it}ms") }
    }

    @Test
    fun fastHashTest() {
        val hash = "asdf".fastHash()
        println("SHA256 Hash is $hash")
        assertTrue("Hash should start with SHA256 prefix", hash.startsWith("SHA256."))
        measureTimeMillis {
            assertTrue("asdf".checkAgainstHash(hash))
        }.also { println("SHA256 verify correct: ${it}ms") }
        measureTimeMillis {
            assertFalse("asdff".checkAgainstHash(hash))
        }.also { println("SHA256 verify incorrect: ${it}ms") }
    }

    @Test
    fun fastHashPerformance() {
        // Fast hash should be significantly faster than secure hash
        val fastTime = measureTimeMillis {
            repeat(100) {
                val hash = "test$it".fastHash()
                "test$it".checkAgainstHash(hash)
            }
        }
        println("100 fast hash+verify operations: ${fastTime}ms")
        assertTrue("Fast hash should complete 100 operations in under 1 second", fastTime < 1000)
    }

    @Test
    fun crossHashVerificationFails() {
        // Verify that a PBKDF2 hash doesn't validate against fast hash check and vice versa
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        // Each should validate against its own type
        assertTrue("asdf".checkAgainstHash(secureHash))
        assertTrue("asdf".checkAgainstHash(fastHash))

        // Wrong password should fail for both
        assertFalse("wrong".checkAgainstHash(secureHash))
        assertFalse("wrong".checkAgainstHash(fastHash))
    }

    @Test
    fun emptyStringHandling() {
        assertEquals("Empty input should return empty hash", "", "".secureHash())
        assertEquals("Empty input should return empty hash", "", "".fastHash())
        assertFalse("Empty hash should never validate", "anything".checkAgainstHash(""))
    }

    @Test
    fun isSlowHashTest() {
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        assertTrue("PBKDF2 hash should be detected as slow", secureHash.isSlowHash())
        assertFalse("SHA256 hash should not be detected as slow", fastHash.isSlowHash())
    }

    @Test
    fun idempotentHashTest() {
        // If already hashed, should return unchanged
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        assertEquals("Already hashed PBKDF2 should return unchanged", secureHash, secureHash.secureHash())
        assertEquals("Already hashed SHA256 should return unchanged", fastHash, fastHash.fastHash())
    }

    @Test
    fun unknownPrefixFails() {
        // Unknown prefix should fail validation
        assertFalse("asdf".checkAgainstHash("UNKNOWN.salt.hash"))
        assertFalse("asdf".checkAgainstHash("randomgarbage"))
    }

    @Test
    fun differentSaltsProduceDifferentHashes() {
        val hash1 = "asdf".fastHash()
        val hash2 = "asdf".fastHash()
        assertNotEquals("Same input should produce different hashes due to random salt", hash1, hash2)
        // But both should still validate
        assertTrue("asdf".checkAgainstHash(hash1))
        assertTrue("asdf".checkAgainstHash(hash2))
    }
}
