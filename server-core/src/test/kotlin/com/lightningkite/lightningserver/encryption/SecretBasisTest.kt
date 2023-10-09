package com.lightningkite.lightningserver.encryption

import org.junit.Assert.*
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SecretBasisTest {
    @Test fun derive() {
        val basis = SecretBasis()
        assertContentEquals(basis.bytes, SecretBasis(basis.string.replace('=', '0')).bytes)
        assertNotEquals(basis.bytes, basis.derive("test"))
        assertEquals(SecretBasis.BYTES, basis.bytes.size)
        assertEquals(SecretBasis.BYTES, basis.derive("test").size)
        assertContentEquals(basis.derive("test"), basis.derive("test"))
        assertTrue(basis.hasher("test").verify("content", basis.hasher("test").sign("content")))
        assertFalse(basis.hasher("test2").verify("content", basis.hasher("test").sign("content")))
        assertEquals("content", basis.encryptor("test").decrypt(basis.encryptor("test").encrypt("content")))
    }
}