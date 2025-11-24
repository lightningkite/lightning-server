package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.HS256
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

class JwtInfrastructureTest {

    @Test
    fun `generateRS256Signer creates valid RSA signer`(): Unit = runBlocking {
        val signer = generateRS256Signer()

        assertNotNull(signer)
        assertTrue(signer is Signer.RSA_PKCS1)
        assertEquals("RS256", signer.name)
    }

    @Test
    fun `generateRS384Signer creates valid RSA signer`(): Unit = runBlocking {
        val signer = generateRS384Signer()

        assertNotNull(signer)
        assertTrue(signer is Signer.RSA_PKCS1)
        assertEquals("RS384", signer.name)
    }

    @Test
    fun `generateRS512Signer creates valid RSA signer`(): Unit = runBlocking {
        val signer = generateRS512Signer()

        assertNotNull(signer)
        assertTrue(signer is Signer.RSA_PKCS1)
        assertEquals("RS512", signer.name)
    }

    @Test
    fun `generatePS256Signer creates valid RSA-PSS signer`(): Unit = runBlocking {
        val signer = generatePS256Signer()

        assertNotNull(signer)
        assertTrue(signer is Signer.RSA_PSS)
        assertEquals("PS256", signer.name)
    }

    @Test
    fun `RSA signer can sign and verify data`(): Unit = runBlocking {
        val signer = generateRS256Signer()
        val testData = "test data to sign".encodeToByteArray()

        // Sign the data
        val signature = signer.generator.generateSignature(testData)

        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        // Verify the signature
        val isValid = signer.verifier.tryVerifySignature(testData, signature)
        assertTrue(isValid, "Signature should be valid")
    }

    @Test
    fun `RSA signer rejects invalid signatures`(): Unit = runBlocking {
        val signer = generateRS256Signer()
        val testData = "test data".encodeToByteArray()

        // Create a signature for different data
        val differentData = "different data".encodeToByteArray()
        val signatureForDifferentData = signer.generator.generateSignature(differentData)

        // Verify that the signature for different data doesn't work with the original data
        val isValid = signer.verifier.tryVerifySignature(testData, signatureForDifferentData)
        assertFalse(isValid, "Signature for different data should be rejected")
    }

    @Test
    fun `JwksUtils converts RSA_PKCS1 signer to JWK`(): Unit = runBlocking {
        val signer = generateRS256Signer()
        val jwk = JwksUtils.toJwk(signer, "test-key-id")

        assertEquals("RSA", jwk.kty)
        assertEquals("sig", jwk.use)
        assertEquals("test-key-id", jwk.kid)
        assertEquals("RS256", jwk.alg)
        assertNotNull(jwk.n, "Modulus should be present")
        assertNotNull(jwk.e, "Exponent should be present")

        // Verify modulus and exponent are base64url encoded
        assertTrue(jwk.n!!.isNotEmpty())
        assertTrue(jwk.e!!.isNotEmpty())
    }

    @Test
    fun `JwksUtils converts RSA_PSS signer to JWK`(): Unit = runBlocking {
        val signer = generatePS256Signer()
        val jwk = JwksUtils.toJwk(signer, "ps256-key")

        assertEquals("RSA", jwk.kty)
        assertEquals("sig", jwk.use)
        assertEquals("ps256-key", jwk.kid)
        assertEquals("PS256", jwk.alg)
        assertNotNull(jwk.n)
        assertNotNull(jwk.e)
    }

    @Test
    fun `JwksUtils rejects non-RSA signers`(): Unit = runBlocking {
        val hmacKey = SecretBasis().HS256("test")

        assertFailsWith<IllegalArgumentException> {
            JwksUtils.toJwk(hmacKey)
        }
    }

    @Test
    fun `JwksUtils creates JWKS response from single signer`(): Unit = runBlocking {
        val signer = generateRS256Signer()
        val jwks = JwksUtils.toJwks(signer, "my-key")

        assertEquals(1, jwks.keys.size)
        assertEquals("my-key", jwks.keys[0].kid)
    }

    @Test
    fun `JwksUtils creates JWKS response from multiple signers`(): Unit = runBlocking {
        val signers = mapOf(
            "key-1" to generateRS256Signer(),
            "key-2" to generateRS384Signer(),
            "key-3" to generateRS512Signer()
        )

        val jwks = JwksUtils.toJwks(signers)

        assertEquals(3, jwks.keys.size)
        assertTrue(jwks.keys.any { it.kid == "key-1" && it.alg == "RS256" })
        assertTrue(jwks.keys.any { it.kid == "key-2" && it.alg == "RS384" })
        assertTrue(jwks.keys.any { it.kid == "key-3" && it.alg == "RS512" })
    }

    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun `JwtIssuer creates valid ID tokens`(): Unit = runBlocking {
        object : ServerBuilder() {}.test({}) {
            val signer = generateRS256Signer()
            val issuer = JwtIssuer(
                signer = signer,
                issuer = "https://test.example.com"
            )

            val claims = issuer.buildClaims(
                sub = "user-123",
                aud = "client-456",
                nonce = "nonce-789",
                authTime = 1234567890
            )

            val idToken = issuer.createIdToken(claims)

            assertNotNull(idToken)

            // JWT should have 3 parts separated by dots
            val parts = idToken.split(".")
            assertEquals(3, parts.size, "JWT should have header, payload, and signature")

            // Decode and verify header (JWT uses Base64 URL-safe without padding)
            val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            val headerJson = decoder.decode(parts[0]).decodeToString()
            assertTrue(headerJson.contains("\"typ\":\"JWT\""))
            assertTrue(headerJson.contains("\"alg\":\"RS256\""))

            // Decode and verify payload
            val payloadJson = decoder.decode(parts[1]).decodeToString()
            assertTrue(payloadJson.contains("\"iss\":\"https://test.example.com\""))
            assertTrue(payloadJson.contains("\"sub\":\"user-123\""))
            assertTrue(payloadJson.contains("\"aud\":\"client-456\""))
            assertTrue(payloadJson.contains("\"nonce\":\"nonce-789\""))
            assertTrue(payloadJson.contains("\"auth_time\":1234567890"))

            // Verify signature
            val signatureInput = "${parts[0]}.${parts[1]}"
            val signature = decoder.decode(parts[2])
            val isValid = signer.verify(signatureInput.encodeToByteArray(), signature)
            assertTrue(isValid, "JWT signature should be valid")
        }
    }

    @Test
    fun `JwtIssuer includes exp and iat claims`(): Unit = runBlocking {
        object : ServerBuilder() {}.test({}) {
            val signer = generateRS256Signer()
            val issuer = JwtIssuer(signer, "https://test.example.com")

            val claims = issuer.buildClaims(sub = "user", aud = "client")

            assertTrue(claims.exp > 0, "Expiration time should be set")
            assertTrue(claims.iat > 0, "Issued at time should be set")
            assertTrue(claims.exp > claims.iat, "Expiration should be after issued at")
        }
    }

    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun `JwtIssuer supports additional claims via builder`(): Unit = runBlocking {
        object : ServerBuilder() {}.test({}) {
            val signer = generateRS256Signer()
            val issuer = JwtIssuer(signer, "https://test.example.com")

            val claims = issuer.buildClaims(
                sub = "user",
                aud = "client"
            ) {
                copy(
                    email = "user@example.com",
                    email_verified = true,
                    name = "Test User"
                )
            }

            assertEquals("user@example.com", claims.email)
            assertEquals(true, claims.email_verified)
            assertEquals("Test User", claims.name)

            // Verify these claims are in the JWT
            val idToken = issuer.createIdToken(claims)

            val parts = idToken.split(".")
            val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            val payloadJson = decoder.decode(parts[1]).decodeToString()

            assertTrue(payloadJson.contains("\"email\":\"user@example.com\""))
            assertTrue(payloadJson.contains("\"email_verified\":true"))
            assertTrue(payloadJson.contains("\"name\":\"Test User\""))
        }
    }

    @Test
    fun `Different RSA signers produce different keys`(): Unit = runBlocking {
        val signer1 = generateRS256Signer()
        val signer2 = generateRS256Signer()

        val jwk1 = JwksUtils.toJwk(signer1, "key1")
        val jwk2 = JwksUtils.toJwk(signer2, "key2")

        // Keys should be different
        assertNotEquals(jwk1.n, jwk2.n, "Different signers should have different moduli")
    }
}
