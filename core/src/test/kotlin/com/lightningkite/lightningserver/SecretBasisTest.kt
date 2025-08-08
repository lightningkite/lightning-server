package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.SecretBasis
import com.lightningkite.lightningserver.definition.encryptor
import com.lightningkite.lightningserver.definition.hasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretBasisTest {
    
    @Test
    fun testSecretBasisCreation() {
        val secret = SecretBasis()
        assertTrue(secret.string.length >= SecretBasis.BASE64_CHARS)
        assertEquals(SecretBasis.BYTES, secret.bytes.size)
    }
    
    @Test
    fun testDerive() {
        val secret = SecretBasis()
        val key1 = "test-key-1"
        val key2 = "test-key-2"
        
        val derived1 = secret.derive(key1)
        val derived2 = secret.derive(key2)
        
        // Same key should produce same result
        assertEquals(
            secret.derive(key1).toList(),
            derived1.toList()
        )
        
        // Different keys should produce different results
        assertNotEquals(
            derived1.toList(),
            derived2.toList()
        )
    }
    
    @Test
    fun testHasher() {
        val secret = SecretBasis()
        val variant = "test-variant"
        val data1 = "test-data-1".toByteArray()
        val data2 = "test-data-2".toByteArray()
        
        val hasher = secret.hasher(variant)
        
        // Same data should produce same hash
        assertEquals(
            hasher.sign(data1).toList(),
            hasher.sign(data1).toList()
        )
        
        // Different data should produce different hashes
        assertNotEquals(
            hasher.sign(data1).toList(),
            hasher.sign(data2).toList()
        )
    }
    
    @Test
    fun testEncryptor() {
        val secret = SecretBasis()
        val variant = "test-variant"
        val data = "test-data-for-encryption".toByteArray()
        
        val encryptor = secret.encryptor(variant)
        
        val encrypted = encryptor.encrypt(data)
        val decrypted = encryptor.decrypt(encrypted)
        
        // Decrypted data should match original data
        assertEquals(
            data.toList(),
            decrypted.toList()
        )
        
        // Encrypted data should be different from original data
        assertNotEquals(
            data.toList(),
            encrypted.toList()
        )
    }
    
    @Test
    fun testFunctionReturningSecretBasis() {
        // Use a fixed SecretBasis with a valid Base64 string
        val fixedSecret = SecretBasis("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+Pw==")
        val secretProvider = { fixedSecret }
        val variant = "test-variant"
        val data = "test-data".toByteArray()
        
        val hasher = secretProvider.hasher(variant)
        val encryptor = secretProvider.encryptor(variant)
        
        // Test hasher
        val hash = hasher().sign(data)
        assertEquals(hash.toList(), hasher().sign(data).toList())
        
        // Test encryptor
        val encrypted = encryptor().encrypt(data)
        val decrypted = encryptor().decrypt(encrypted)
        assertEquals(data.toList(), decrypted.toList())
    }
}