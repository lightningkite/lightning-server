// by Claude
package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for SecureHash utility functions.
 */
class SecureHashTest {

    // ========== secureHash Tests ==========

    @Test
    fun `secureHash produces non-empty result`() = runBlocking {
        val hash = "password123".secureHash()
        assertTrue(hash.isNotEmpty())
        assertTrue(hash.startsWith("PBKDF2WithHmacSHA512."))
    }

    @Test
    fun `secureHash produces different hashes for same input due to random salt`() = runBlocking {
        val password = "samePassword"
        val hash1 = password.secureHash()
        val hash2 = password.secureHash()
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `secureHash is idempotent for already hashed values`() = runBlocking {
        val password = "myPassword"
        val hash1 = password.secureHash()
        val hash2 = hash1.secureHash()
        assertEquals(hash1, hash2)
    }

    @Test
    fun `secureHash returns empty for empty string`() = runBlocking {
        val hash = "".secureHash()
        assertEquals("", hash)
    }

    // ========== fastHash Tests ==========

    @Test
    fun `fastHash produces non-empty result`() {
        val hash = "token123".fastHash()
        assertTrue(hash.isNotEmpty())
        assertTrue(hash.startsWith("SHA256."))
    }

    @Test
    fun `fastHash produces different hashes for same input due to random salt`() {
        val token = "sameToken"
        val hash1 = token.fastHash()
        val hash2 = token.fastHash()
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `fastHash is idempotent for already hashed values`() {
        val token = "myToken"
        val hash1 = token.fastHash()
        val hash2 = hash1.fastHash()
        assertEquals(hash1, hash2)
    }

    @Test
    fun `fastHash returns empty for empty string`() {
        val hash = "".fastHash()
        assertEquals("", hash)
    }

    // ========== checkAgainstHash Tests ==========

    @Test
    fun `checkAgainstHash verifies correct password`() = runBlocking {
        val password = "correctPassword"
        val hash = password.secureHash()
        assertTrue(password.checkAgainstHash(hash))
    }

    @Test
    fun `checkAgainstHash rejects wrong password`() = runBlocking {
        val password = "correctPassword"
        val hash = password.secureHash()
        assertFalse("wrongPassword".checkAgainstHash(hash))
    }

    @Test
    fun `checkAgainstHash returns false for empty hash`() = runBlocking {
        assertFalse("anyPassword".checkAgainstHash(""))
    }

    @Test
    fun `checkAgainstHash returns false for invalid hash format`() = runBlocking {
        assertFalse("anyPassword".checkAgainstHash("invalidHashFormat"))
    }

    @Test
    fun `checkAgainstHash works with fastHash`() {
        val token = "mySessionToken"
        val hash = token.fastHash()
        assertTrue(token.checkAgainstFastHashDirect(hash))
    }

    @Test
    fun `checkAgainstHash rejects wrong token with fastHash`() {
        val token = "mySessionToken"
        val hash = token.fastHash()
        assertFalse("wrongToken".checkAgainstFastHashDirect(hash))
    }

    // ========== isSlowHash Tests ==========

    @Test
    fun `isSlowHash returns true for PBKDF2 hashes`() = runBlocking {
        val hash = "password".secureHash()
        assertTrue(hash.isSlowHash())
    }

    @Test
    fun `isSlowHash returns false for fast hashes`() {
        val hash = "token".fastHash()
        assertFalse(hash.isSlowHash())
    }

    @Test
    fun `isSlowHash returns false for empty string`() {
        assertFalse("".isSlowHash())
    }

    @Test
    fun `isSlowHash returns false for plain text`() {
        assertFalse("notAHash".isSlowHash())
    }

    // ========== Java Implementation Tests ==========

    @Test
    fun `secureHashJava produces valid hash`() {
        val hash = "password".secureHashJava()
        assertTrue(hash.isNotEmpty())
        assertTrue(hash.startsWith("PBKDF2WithHmacSHA512."))
    }

    @Test
    fun `secureHashJava is idempotent`() {
        val password = "myPassword"
        val hash1 = password.secureHashJava()
        val hash2 = hash1.secureHashJava()
        assertEquals(hash1, hash2)
    }

    @Test
    fun `checkAgainstHashJava verifies correct password`() {
        val password = "correctPassword"
        val hash = password.secureHashJava()
        assertTrue(password.checkAgainstHashJava(hash))
    }

    @Test
    fun `checkAgainstHashJava rejects wrong password`() {
        val password = "correctPassword"
        val hash = password.secureHashJava()
        assertFalse("wrongPassword".checkAgainstHashJava(hash))
    }

    @Test
    fun `checkAgainstHashJava returns false for empty hash`() {
        assertFalse("anyPassword".checkAgainstHashJava(""))
    }

    // Helper to test fast hash verification directly
    private fun String.checkAgainstFastHashDirect(hash: String): Boolean {
        if (hash.isEmpty() || !hash.startsWith("SHA256.")) return false
        val against = hash.removePrefix("SHA256.")
        val salt = kotlin.io.encoding.Base64.decode(against.substringBefore('.'))
        val expectedHash = against.substringAfter('.')
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(this.toByteArray(Charsets.UTF_8))
        return kotlin.io.encoding.Base64.encode(digest.digest()) == expectedHash
    }
}
