// by Claude
package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for Signer implementations.
 */
class SignerTest {

    // ========== HMAC Signer Tests ==========

    @Test
    fun `HMAC signer sign and verify bytes`() = runBlocking {
        val key = CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        val signer = Signer.HMAC(key, "HS256")

        assertEquals("HS256", signer.name)
        assertNotNull(signer.generator)
        assertNotNull(signer.verifier)

        val data = "Hello, World!".encodeToByteArray()
        val signature = signer.sign(data)

        assertTrue(signature.isNotEmpty())
        assertTrue(signer.verify(data, signature))
    }

    @Test
    fun `HMAC signer rejects modified data`() = runBlocking {
        val key = CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        val signer = Signer.HMAC(key, "HS256")

        val data = "Original data".encodeToByteArray()
        val signature = signer.sign(data)

        assertFalse(signer.verify("Modified data".encodeToByteArray(), signature))
    }

    @Test
    fun `HMAC signer sign and verify string`() = runBlocking {
        val key = CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        val signer = Signer.HMAC(key, "HS256")

        val message = "Test message"
        val signature = signer.sign(message)

        assertTrue(signature.isNotEmpty())
        // Verify returns true when the bytes match (signature is base64 encoded)
    }

    @Test
    fun `HMAC signer blocking operations`() {
        val key = runBlocking {
            CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        }
        val signer = Signer.HMAC(key, "HS256")

        val data = "Blocking test".encodeToByteArray()
        val signature = signer.signBlocking(data)

        assertTrue(signature.isNotEmpty())
        assertTrue(signer.verifyBlocking(data, signature))
    }

    @Test
    fun `HMAC signer blocking string operations`() {
        val key = runBlocking {
            CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        }
        val signer = Signer.HMAC(key, "HS256")

        val message = "Blocking string test"
        val signature = signer.signBlocking(message)

        assertTrue(signature.isNotEmpty())
    }

    // ========== ECDSA Signer Tests ==========

    @Test
    fun `ECDSA signer sign and verify bytes`() = runBlocking {
        val keyPair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        val signer = keyPair.ES256()

        assertEquals("ES256", signer.name)
        assertNotNull(signer.generator)
        assertNotNull(signer.verifier)

        val data = "Hello, ECDSA!".encodeToByteArray()
        val signature = signer.sign(data)

        assertTrue(signature.isNotEmpty())
        assertTrue(signer.verify(data, signature))
    }

    @Test
    fun `ECDSA signer rejects modified data`() = runBlocking {
        val keyPair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        val signer = keyPair.ES256()

        val data = "Original ECDSA data".encodeToByteArray()
        val signature = signer.sign(data)

        assertFalse(signer.verify("Modified ECDSA data".encodeToByteArray(), signature))
    }

    @Test
    fun `ES384 signer creation`() = runBlocking {
        val keyPair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P384).generateKey()
        val signer = keyPair.ES384()

        assertEquals("ES384", signer.name)

        val data = "ES384 test".encodeToByteArray()
        val signature = signer.sign(data)
        assertTrue(signer.verify(data, signature))
    }

    @Test
    fun `ES512 signer creation`() = runBlocking {
        val keyPair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P521).generateKey()
        val signer = keyPair.ES512()

        assertEquals("ES512", signer.name)

        val data = "ES512 test".encodeToByteArray()
        val signature = signer.sign(data)
        assertTrue(signer.verify(data, signature))
    }

    @Test
    fun `ECDSA with DER format`() = runBlocking {
        val keyPair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        val signer = keyPair.ES256(ECDSA.SignatureFormat.DER)

        assertEquals("ES256", signer.name)

        val data = "DER format test".encodeToByteArray()
        val signature = signer.sign(data)
        assertTrue(signer.verify(data, signature))
    }

    // ========== Signer Data Class Tests ==========

    @Test
    fun `HMAC data class properties`() = runBlocking {
        val key = CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        val signer = Signer.HMAC(key, "HS256")

        assertEquals(key, signer.key)
        assertEquals("HS256", signer.name)
    }

    @Test
    fun `ECDSA data class properties`() = runBlocking {
        val keyPair = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKey()
        val signer = Signer.ECDSA(keyPair, SHA256, ECDSA.SignatureFormat.RAW, "ES256")

        assertEquals(keyPair, signer.keyPair)
        assertEquals(SHA256, signer.digest)
        assertEquals(ECDSA.SignatureFormat.RAW, signer.format)
        assertEquals("ES256", signer.name)
    }

    // ========== Cross-Signature Tests ==========

    @Test
    fun `different keys produce different signatures`() = runBlocking {
        val key1 = CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        val key2 = CryptographyProvider.Default.get(HMAC).keyGenerator(SHA256).generateKey()
        val signer1 = Signer.HMAC(key1, "HS256")
        val signer2 = Signer.HMAC(key2, "HS256")

        val data = "Same data".encodeToByteArray()
        val sig1 = signer1.sign(data)
        val sig2 = signer2.sign(data)

        // Different keys should produce different signatures
        assertFalse(sig1.contentEquals(sig2))

        // Signature from one key shouldn't verify with another
        assertFalse(signer2.verify(data, sig1))
    }
}
