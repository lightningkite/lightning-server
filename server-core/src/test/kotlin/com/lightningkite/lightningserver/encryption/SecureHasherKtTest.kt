package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.encryption.*
import org.junit.Assert.*
import org.junit.Test

class SecureHasherKtTest {
    @Test fun sign() {
        val hasher = SecretBasis().hasher("test")
        assertTrue(hasher.verify("TEST", hasher.sign("TEST")))
        val claims = JwtClaims(
            iss = "test",
            sub = "Test",
            aud = "teSt",
            exp = System.currentTimeMillis() / 1000 + 1000,
            iat = System.currentTimeMillis() / 1000
        )
        assertEquals(claims, hasher.verifyJwt(hasher.signJwt(claims)))
    }
}