package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SecretBasisTest {

    @Test
    fun testSecretBasisGeneration() {
        val basis1 = SecretBasis()
        val basis2 = SecretBasis()

        // Different instances should have different secrets
        assertNotEquals(basis1.string, basis2.string)

        // String should be Base64 encoded and non-empty
        // Note: The actual Base64 length depends on the key format used by the cryptography library
        assert(basis1.string.isNotEmpty()) { "Secret basis string should not be empty" }
        assert(basis1.bytes.size == SecretBasis.BYTES) { "Decoded bytes should be exactly ${SecretBasis.BYTES} bytes" }
    }

    @Test
    fun testSecretBasisSerialization() {
        val basis = SecretBasis()
        val reconstructed = SecretBasis(basis.string)

        // Should be able to reconstruct from string
        assertContentEquals(basis.bytes, reconstructed.bytes)
    }

    @Test
    fun testDerivationConsistency(): Unit = runBlocking {
        val basis = SecretBasis()

        // Same variant should produce same derived key
        val derived1 = basis.derive("test-variant")
        val derived2 = basis.derive("test-variant")

        assertContentEquals(derived1, derived2)
    }

    @Test
    fun testDerivationUniqueness(): Unit = runBlocking {
        val basis = SecretBasis()

        // Different variants should produce different derived keys
        val derived1 = basis.derive("variant-1")
        val derived2 = basis.derive("variant-2")

        assert(!derived1.contentEquals(derived2)) {
            "Different variants should produce different keys"
        }
    }

    @Test
    fun testBlockingVsSuspending(): Unit = runBlocking {
        val basis = SecretBasis()

        // Blocking and suspending versions should produce same results
        val suspended = basis.derive("test")
        val blocking = basis.deriveBlocking("test")

        assertContentEquals(suspended, blocking)
    }

    @Test
    fun testByteArrayVsStringDerivation(): Unit = runBlocking {
        val basis = SecretBasis()
        val variant = "test-variant"

        // String and byte array versions should produce same results
        val fromString = basis.derive(variant)
        val fromBytes = basis.derive(variant.encodeToByteArray())

        assertContentEquals(fromString, fromBytes)
    }
}
