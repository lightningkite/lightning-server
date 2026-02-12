package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.verify
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.sessions.token.JwtClaims
import com.lightningkite.lightningserver.sessions.token.JwtHeader
import com.lightningkite.lightningserver.sessions.token.JwtSignatureException
import com.lightningkite.services.http.client
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256 as CryptoSHA256
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Verifies Apple ID tokens (JWTs) with proper signature validation.
 *
 * Apple Sign In returns an `id_token` which is a JWT that must be cryptographically verified
 * before trusting its contents. This verifier:
 * - Fetches Apple's public keys from their JWKS endpoint
 * - Caches the keys for performance
 * - Verifies JWT signature using RSA
 * - Validates issuer, audience, and expiration claims
 *
 * **Security:**
 * This implementation prevents token forgery by verifying the cryptographic signature
 * using Apple's public keys, unlike manual base64 decoding which is vulnerable to attacks.
 *
 * @see <a href="https://developer.apple.com/documentation/sign_in_with_apple/sign_in_with_apple_rest_api/verifying_a_user">Apple Documentation</a>
 */
public object AppleJwtVerifier {
    private const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
    private const val APPLE_ISSUER = "https://appleid.apple.com"

    private var cachedKeys: Map<String, Signer>? = null
    private var cacheTime: Instant? = null
    private val cacheExpiration = 24.hours

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class AppleJwks(
        val keys: List<ApplePublicKey>
    )

    @Serializable
    private data class ApplePublicKey(
        val kty: String,  // Key type (RSA)
        val kid: String,  // Key ID
        val use: String,  // Usage (sig for signature)
        val alg: String,  // Algorithm (RS256)
        val n: String,    // RSA modulus (base64url)
        val e: String     // RSA exponent (base64url)
    )

    /**
     * Fetches Apple's current public keys from their JWKS endpoint and converts them to signers.
     * Results are cached for 24 hours to reduce network calls.
     */
    context(runtime: ServerRuntime)
    private suspend fun getApplePublicKeys(): Map<String, Signer> {
        val now = now()

        // Return cached keys if still valid
        cachedKeys?.let { keys ->
            cacheTime?.let { time ->
                if (now < time + cacheExpiration) {
                    return keys
                }
            }
        }

        // Fetch new keys from Apple
        val response = client.get(APPLE_JWKS_URL).body<AppleJwks>()
        val signerMap = response.keys.associate { key ->
            key.kid to createRsaVerifier(key)
        }
        cachedKeys = signerMap
        cacheTime = now
        return signerMap
    }

    /**
     * Verifies an Apple ID token and extracts the claims.
     *
     * @param idToken The JWT id_token from Apple's OAuth response
     * @param expectedAudience The expected audience (your app's client ID)
     * @return The verified JWT claims
     * @throws JwtSignatureException if signature verification fails
     * @throws BadRequestException if token is malformed or validation fails
     */
    @OptIn(ExperimentalEncodingApi::class)
    context(runtime: ServerRuntime)
    public suspend fun verifyAppleIdToken(
        idToken: String,
        expectedAudience: String
    ): JwtClaims {
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        // Parse JWT parts
        val parts = idToken.split('.')
        if (parts.size != 3) {
            throw BadRequestException("Invalid JWT format: expected 3 parts, got ${parts.size}")
        }

        val headerJson = decoder.decode(parts[0]).decodeToString()
        val header = json.decodeFromString<JwtHeader>(headerJson)

        val claimsJson = decoder.decode(parts[1]).decodeToString()
        val claims = json.decodeFromString<JwtClaims>(claimsJson)

        val signature = decoder.decode(parts[2])

        // Validate algorithm is RS256
        if (header.alg != "RS256") {
            throw JwtSignatureException("Unsupported algorithm: ${header.alg}. Apple uses RS256.")
        }

        // Validate issuer
        if (claims.iss != APPLE_ISSUER) {
            throw BadRequestException("Invalid issuer: expected $APPLE_ISSUER, got ${claims.iss}")
        }

        // Validate audience
        if (claims.aud != expectedAudience) {
            throw BadRequestException("Invalid audience: expected $expectedAudience, got ${claims.aud}")
        }

        // Validate expiration
        val now = now()
        if (now.epochSeconds > claims.exp) {
            throw BadRequestException("Token has expired")
        }

        // Validate not-before if present
        claims.nbf?.let { nbf ->
            if (now.epochSeconds < nbf) {
                throw BadRequestException("Token not valid yet")
            }
        }

        // Fetch Apple's public keys
        val appleKeys = getApplePublicKeys()

        // Find the key that matches the kid in the header
        // Note: JWT kid is in the header, not typ
        val kid = (json.parseToJsonElement(headerJson) as? JsonObject)
            ?.get("kid")?.let { (it as JsonPrimitive).content }
            ?: throw BadRequestException("Missing 'kid' in JWT header")

        val signer = appleKeys[kid]
            ?: throw JwtSignatureException("No matching public key found for kid: $kid")

        // Verify signature using the public key
        val signatureInput = "${parts[0]}.${parts[1]}".encodeToByteArray()

        if (!signer.verify(signatureInput, signature)) {
            throw JwtSignatureException("JWT signature verification failed")
        }

        return claims
    }

    /**
     * Creates an RSA signature verifier from Apple's public key.
     */
    @OptIn(ExperimentalEncodingApi::class)
    context(runtime: ServerRuntime)
    private fun createRsaVerifier(key: ApplePublicKey): Signer {
        if (key.kty != "RSA") {
            throw IllegalArgumentException("Expected RSA key type, got ${key.kty}")
        }
        if (key.alg != "RS256") {
            throw IllegalArgumentException("Expected RS256 algorithm, got ${key.alg}")
        }

        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        // Decode modulus and exponent
        val nBytes = decoder.decode(key.n)
        val eBytes = decoder.decode(key.e)

        // Create RSA public key from components
        val crypto = CryptographyProvider.Default
        val rsaPkcs1 = crypto.get(RSA.PKCS1)

        // Construct DER-encoded public key in SPKI/X.509 format
        val publicKey = rsaPkcs1.publicKeyDecoder(CryptoSHA256).decodeFromByteArrayBlocking(
            RSA.PublicKey.Format.DER,
            constructRsaPublicKeyDer(nBytes, eBytes)
        )

        // Create a verification-only signer
        return object : Signer {
            override val generator = throw UnsupportedOperationException("This signer is verification-only")
            override val verifier = publicKey.signatureVerifier()
            override val name = "RS256"
        }
    }

    /**
     * Constructs a DER-encoded RSA public key in SPKI/X.509 format from modulus and exponent.
     *
     * Format: SEQUENCE { SEQUENCE { OID rsaEncryption, NULL }, BIT STRING { SEQUENCE { INTEGER n, INTEGER e } } }
     */
    private fun constructRsaPublicKeyDer(n: ByteArray, e: ByteArray): ByteArray {
        // RSA encryption OID: 1.2.840.113549.1.1.1
        val rsaOid = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)

        // Encode DER length field
        fun encodeDerLength(length: Int): ByteArray {
            return when {
                length < 128 -> byteArrayOf(length.toByte())
                length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
                else -> {
                    val bytes = byteArrayOf((length shr 8).toByte(), (length and 0xFF).toByte())
                    byteArrayOf(0x82.toByte()) + bytes
                }
            }
        }

        // Encode an integer with proper DER formatting
        fun encodeInteger(value: ByteArray): ByteArray {
            // Add leading zero if high bit is set (to indicate positive number in two's complement)
            val needsLeadingZero = value.isNotEmpty() && (value[0].toInt() and 0x80) != 0
            val content = if (needsLeadingZero) byteArrayOf(0) + value else value
            return byteArrayOf(0x02) + encodeDerLength(content.size) + content
        }

        // Build RSA key SEQUENCE { n, e }
        val nEncoded = encodeInteger(n)
        val eEncoded = encodeInteger(e)
        val keySequence = byteArrayOf(0x30) + encodeDerLength(nEncoded.size + eEncoded.size) + nEncoded + eEncoded

        // Wrap in BIT STRING
        val bitString = byteArrayOf(0x03) + encodeDerLength(keySequence.size + 1) + byteArrayOf(0x00) + keySequence

        // Algorithm identifier SEQUENCE { OID, NULL }
        val algorithm = byteArrayOf(0x30) + encodeDerLength(rsaOid.size + 4) +
                       byteArrayOf(0x06) + byteArrayOf(rsaOid.size.toByte()) + rsaOid +
                       byteArrayOf(0x05, 0x00) // NULL

        // Full SPKI SEQUENCE
        val fullSequence = algorithm + bitString
        return byteArrayOf(0x30) + encodeDerLength(fullSequence.size) + fullSequence
    }
}
