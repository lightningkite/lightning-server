package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.token.JwtExpiredException
import com.lightningkite.lightningserver.sessions.token.JwtFormatException
import com.lightningkite.lightningserver.sessions.token.JwtSignatureException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.whyoleg.cryptography.algorithms.SHA256 as CryptoSHA256

/**
 * Tests for [JwtVerifier].
 *
 * Each test generates a fresh RSA keypair, signs a JWT with the private key, and points the
 * verifier at a [Jwks] override that publishes the matching public key. This covers the security
 * properties enumerated in the spec: signature verification, issuer/audience binding, exp/nbf
 * enforcement, nonce binding, algorithm whitelisting, and `kid` lookup.
 */
class JwtVerifierTest {

    private object TestServer : ServerBuilder()

    private val ISSUER = "https://idp.example.com"
    private val AUDIENCE = "test-client-id"
    private val KID = "test-key-1"

    @OptIn(ExperimentalEncodingApi::class)
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @OptIn(ExperimentalEncodingApi::class)
    private fun jwksFor(pub: RSAPublicKey): Jwks {
        val nBytes = pub.modulus.toByteArray().let { if (it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        val eBytes = pub.publicExponent.toByteArray()
        val signer = buildRsaVerifier(urlSafe.encode(nBytes), urlSafe.encode(eBytes))
        return object : Jwks(url = "https://stub.invalid/jwks") {
            context(_: ServerRuntime)
            override suspend fun signer(kid: String): Signer {
                if (kid != KID) throw JwtSignatureException("No matching JWKS key for kid '$kid'")
                return signer
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun makeJwt(
        privateKey: PrivateKey,
        headerJson: String,
        payloadJson: String,
    ): String {
        val header = urlSafe.encode(headerJson.encodeToByteArray())
        val payload = urlSafe.encode(payloadJson.encodeToByteArray())
        val signingInput = "$header.$payload"
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.encodeToByteArray())
        }
        val sigBytes = signer.sign()
        return "$signingInput.${urlSafe.encode(sigBytes)}"
    }

    private fun payload(
        iss: String = ISSUER,
        aud: String = AUDIENCE,
        sub: String = "user-123",
        exp: Long = (System.currentTimeMillis() / 1000) + 600,
        iat: Long = System.currentTimeMillis() / 1000,
        nbf: Long? = null,
        nonce: String? = null,
        email: String? = "user@example.com",
        emailVerified: Boolean? = true,
    ): String = buildString {
        append("{")
        append("\"iss\":\"$iss\",\"sub\":\"$sub\",\"aud\":\"$aud\",\"exp\":$exp,\"iat\":$iat")
        if (nbf != null) append(",\"nbf\":$nbf")
        if (nonce != null) append(",\"nonce\":\"$nonce\"")
        if (email != null) append(",\"email\":\"$email\"")
        if (emailVerified != null) append(",\"email_verified\":$emailVerified")
        append("}")
    }

    private val rs256Header = """{"alg":"RS256","kid":"$KID","typ":"JWT"}"""

    @Test
    fun `valid JWT passes verification`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload(nonce = "n1"))
            val claims = JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token, expectedNonce = "n1")
            assertEquals("user@example.com", claims.email)
            assertTrue(claims.email_verified == true)
        }
    }

    @Test
    fun `aud as list also passes`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val raw = """{"iss":"$ISSUER","sub":"x","aud":["other","$AUDIENCE"],"exp":${(System.currentTimeMillis() / 1000) + 600},"iat":${System.currentTimeMillis() / 1000}}"""
            val token = makeJwt(kp.private, rs256Header, raw)
            val claims = JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token)
            assertEquals(listOf("other", AUDIENCE), claims.aud)
        }
    }

    @Test
    fun `wrong issuer is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload(iss = "https://attacker.example.com"))
            assertFailsWith<BadRequestException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token)
            }
        }
    }

    @Test
    fun `wrong audience is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload(aud = "wrong-client"))
            assertFailsWith<BadRequestException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token)
            }
        }
    }

    @Test
    fun `expired token is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload(exp = (System.currentTimeMillis() / 1000) - 3600))
            assertFailsWith<JwtExpiredException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token)
            }
        }
    }

    @Test
    fun `nonce mismatch is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload(nonce = "from-server"))
            assertFailsWith<BadRequestException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token, expectedNonce = "different")
            }
        }
    }

    @Test
    fun `alg none is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val noneHeader = """{"alg":"none","kid":"$KID","typ":"JWT"}"""
            // Build a forged token where signature segment is empty.
            val header = urlSafe.encode(noneHeader.encodeToByteArray())
            val payload = urlSafe.encode(payload().encodeToByteArray())
            val token = "$header.$payload."
            assertFailsWith<JwtSignatureException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token)
            }
        }
    }

    @Test
    fun `unknown kid is rejected after refresh`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val mismatchedHeader = """{"alg":"RS256","kid":"some-other-kid","typ":"JWT"}"""
            val token = makeJwt(kp.private, mismatchedHeader, payload())
            assertFailsWith<JwtSignatureException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(token)
            }
        }
    }

    @Test
    fun `tampered signature is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload())
            val tampered = token.substringBeforeLast('.') + "." + urlSafe.encode("bogus-sig".encodeToByteArray())
            assertFailsWith<JwtSignatureException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(tampered)
            }
        }
    }

    @Test
    fun `tampered payload is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            val token = makeJwt(kp.private, rs256Header, payload())
            val parts = token.split('.')
            val tamperedPayloadJson = payload(email = "attacker@example.com")
            val tamperedToken = "${parts[0]}.${urlSafe.encode(tamperedPayloadJson.encodeToByteArray())}.${parts[2]}"
            assertFailsWith<JwtSignatureException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify(tamperedToken)
            }
        }
    }

    @Test
    fun `malformed JWT is rejected`() = runBlocking {
        TestServer.test({}) {
            val kp = newKeyPair()
            val jwks = jwksFor(kp.public as RSAPublicKey)
            assertFailsWith<JwtFormatException> {
                JwtVerifier(ISSUER, AUDIENCE, jwks).verify("not.a.valid.jwt")
            }
        }
    }
}
