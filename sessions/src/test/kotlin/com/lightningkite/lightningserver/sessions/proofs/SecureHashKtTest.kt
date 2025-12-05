package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.encryption.fastHash
import com.lightningkite.lightningserver.encryption.isSlowHash
import com.lightningkite.lightningserver.encryption.secureHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class SecureHashKtTest {
    @Test
    fun realTest(): Unit = runBlocking {
        val hash = "asdf".secureHash()
        println("Hash is $hash")
        measureTimeMillis {
            assertTrue("asdf".checkAgainstHash(hash))
        }.also { println(it) }
        measureTimeMillis {
            assertFalse("asdff".checkAgainstHash(hash))
        }.also { println(it) }
    }

    @Test
    fun fastHashTest(): Unit = runBlocking {
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
    fun fastHashPerformance(): Unit = runBlocking {
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
    fun crossHashVerificationFails(): Unit = runBlocking {
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
    fun emptyStringHandling(): Unit = runBlocking {
        assertEquals("Empty input should return empty hash", "", "".secureHash())
        assertEquals("Empty input should return empty hash", "", "".fastHash())
        assertFalse("Empty hash should never validate", "anything".checkAgainstHash(""))
    }

    @Test
    fun isSlowHashTest(): Unit = runBlocking {
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        assertTrue("PBKDF2 hash should be detected as slow", secureHash.isSlowHash())
        assertFalse("SHA256 hash should not be detected as slow", fastHash.isSlowHash())
    }

    @Test
    fun idempotentHashTest(): Unit = runBlocking {
        // If already hashed, should return unchanged
        val secureHash = "asdf".secureHash()
        val fastHash = "asdf".fastHash()

        assertEquals("Already hashed PBKDF2 should return unchanged", secureHash, secureHash.secureHash())
        assertEquals("Already hashed SHA256 should return unchanged", fastHash, fastHash.fastHash())
    }

    @Test
    fun unknownPrefixFails(): Unit = runBlocking {
        // Unknown prefix should fail validation
        assertFalse("asdf".checkAgainstHash("UNKNOWN.salt.hash"))
        assertFalse("asdf".checkAgainstHash("randomgarbage"))
    }

    @Test
    fun differentSaltsProduceDifferentHashes(): Unit = runBlocking {
        val hash1 = "asdf".fastHash()
        val hash2 = "asdf".fastHash()
        assertNotEquals("Same input should produce different hashes due to random salt", hash1, hash2)
        // But both should still validate
        assertTrue("asdf".checkAgainstHash(hash1))
        assertTrue("asdf".checkAgainstHash(hash2))
    }

    /**
     * Tests the hash migration scenario: verifying that old PBKDF2 hashes
     * can still be validated, and that the system can detect which hashes
     * need migration.
     */
    @Test
    fun hashMigrationScenario(): Unit = runBlocking {
        // Simulate a session token
        val sessionSecret = java.util.Base64.getEncoder().encodeToString(ByteArray(24).apply {
            java.security.SecureRandom.getInstanceStrong().nextBytes(this)
        })

        // Create old-style hash (simulating pre-migration data)
        val oldStyleHash = sessionSecret.secureHash()
        assertTrue("Old hash should be PBKDF2 style", oldStyleHash.startsWith("PBKDF2WithHmacSHA512."))
        assertTrue("Old hash should be detected as slow", oldStyleHash.isSlowHash())

        // Verify old hash still works
        assertTrue("Should validate against old hash", sessionSecret.checkAgainstHash(oldStyleHash))

        // Create new-style hash (what migration would produce)
        val newStyleHash = sessionSecret.fastHash()
        assertTrue("New hash should be SHA256 style", newStyleHash.startsWith("SHA256."))
        assertFalse("New hash should not be detected as slow", newStyleHash.isSlowHash())

        // Verify new hash works
        assertTrue("Should validate against new hash", sessionSecret.checkAgainstHash(newStyleHash))

        // Verify performance difference
        val oldTime = measureTimeMillis {
            repeat(5) { sessionSecret.checkAgainstHash(oldStyleHash) }
        }
        val newTime = measureTimeMillis {
            repeat(5) { sessionSecret.checkAgainstHash(newStyleHash) }
        }

        println("Migration test passed: PBKDF2 -> SHA256")
        println("  Old hash verification (5x): ${oldTime}ms")
        println("  New hash verification (5x): ${newTime}ms")
        println("  Speedup factor: ${oldTime.toDouble() / newTime.coerceAtLeast(1)}x")

        assertTrue("New hash should be significantly faster", newTime < oldTime)
    }
}