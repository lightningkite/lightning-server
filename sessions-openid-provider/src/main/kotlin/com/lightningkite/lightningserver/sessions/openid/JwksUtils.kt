@file:OptIn(ExperimentalEncodingApi::class)

package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.encryption.Signer
import dev.whyoleg.cryptography.algorithms.RSA
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Utilities for working with JWKS (JSON Web Key Sets)
 *
 * JWKS is the standard format for publishing public keys used to verify JWT signatures.
 * OpenID Connect providers must publish their public keys at a JWKS endpoint.
 */
public object JwksUtils {

    /**
     * Converts an RSA signer to a JWK (JSON Web Key) for publishing
     *
     * @param signer The RSA signer (must be RSA_PKCS1 or RSA_PSS)
     * @param keyId Optional key ID (kid). If not provided, a random UUID is used.
     * @return JsonWebKey representation of the public key
     */
    public fun toJwk(signer: Signer, keyId: String = Uuid.random().toString()): JsonWebKey {
        return when (signer) {
            is Signer.RSA_PKCS1 -> rsaPkcs1ToJwk(signer, keyId)
            is Signer.RSA_PSS -> rsaPssToJwk(signer, keyId)
            else -> throw IllegalArgumentException("Only RSA signers are supported for JWKS. Got: ${signer::class.simpleName}")
        }
    }

    /**
     * Converts multiple signers to a JWKS response
     *
     * @param signers Map of key ID to signer
     * @return JwksResponse containing all public keys
     */
    public fun toJwks(signers: Map<String, Signer>): JwksResponse {
        return JwksResponse(
            keys = signers.map { (kid, signer) -> toJwk(signer, kid) }
        )
    }

    /**
     * Converts a single signer to a JWKS response
     *
     * @param signer The signer
     * @param keyId Optional key ID
     * @return JwksResponse with single key
     */
    public fun toJwks(signer: Signer, keyId: String = "default"): JwksResponse {
        return JwksResponse(keys = listOf(toJwk(signer, keyId)))
    }

    private fun rsaPkcs1ToJwk(signer: Signer.RSA_PKCS1, keyId: String): JsonWebKey {
        val publicKey = signer.keyPair.publicKey
        val (n, e) = extractRsaPublicKeyComponents(publicKey)

        return JsonWebKey(
            kty = "RSA",
            use = "sig",
            kid = keyId,
            alg = signer.name,  // RS256, RS384, or RS512
            n = n,
            e = e
        )
    }

    private fun rsaPssToJwk(signer: Signer.RSA_PSS, keyId: String): JsonWebKey {
        val publicKey = signer.keyPair.publicKey
        val (n, e) = extractRsaPublicKeyComponents(publicKey)

        return JsonWebKey(
            kty = "RSA",
            use = "sig",
            kid = keyId,
            alg = signer.name,  // PS256, PS384, or PS512
            n = n,
            e = e
        )
    }

    /**
     * Extracts RSA modulus (n) and exponent (e) from public key
     *
     * The public key is in DER format (SPKI/X.509). We need to extract the raw
     * modulus and exponent values and encode them as base64url.
     */
    private fun extractRsaPublicKeyComponents(publicKey: RSA.PublicKey): Pair<String, String> {
        // Export public key in DER format
        val derBytes = publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)

        // Parse DER to extract modulus and exponent
        // DER structure for RSA public key (SPKI):
        // SEQUENCE {
        //   SEQUENCE { OID, NULL },
        //   BIT STRING {
        //     SEQUENCE {
        //       INTEGER (modulus),
        //       INTEGER (exponent)
        //     }
        //   }
        // }

        try {
            val components = parseDerRsaPublicKey(derBytes)
            val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

            return Pair(
                encoder.encode(components.modulus),
                encoder.encode(components.exponent)
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse RSA public key DER encoding", e)
        }
    }

    @Serializable
    private data class RsaComponents(
        val modulus: ByteArray,
        val exponent: ByteArray
    )

    /**
     * Parses DER-encoded RSA public key to extract modulus and exponent
     */
    private fun parseDerRsaPublicKey(der: ByteArray): RsaComponents {
        var offset = 0

        // Helper to read DER tag and length
        fun readTagAndLength(): Pair<Int, Int> {
            val tag = der[offset++].toInt() and 0xFF
            var length = der[offset++].toInt() and 0xFF

            if (length and 0x80 != 0) {
                val numLengthBytes = length and 0x7F
                length = 0
                repeat(numLengthBytes) {
                    length = (length shl 8) or (der[offset++].toInt() and 0xFF)
                }
            }
            return tag to length
        }

        // Read outer SEQUENCE
        val (outerTag, outerLength) = readTagAndLength()
        require(outerTag == 0x30) { "Expected SEQUENCE tag (0x30), got ${outerTag.toString(16)}" }

        // Read algorithm identifier SEQUENCE
        val (algTag, algLength) = readTagAndLength()
        require(algTag == 0x30) { "Expected SEQUENCE tag for algorithm" }
        offset += algLength  // Skip algorithm identifier

        // Read BIT STRING containing the actual key
        val (bitStringTag, bitStringLength) = readTagAndLength()
        require(bitStringTag == 0x03) { "Expected BIT STRING tag (0x03)" }
        offset++  // Skip unused bits byte (should be 0x00)

        // Read inner SEQUENCE (contains modulus and exponent)
        val (innerTag, innerLength) = readTagAndLength()
        require(innerTag == 0x30) { "Expected SEQUENCE tag for key components" }

        // Read modulus INTEGER
        val (nTag, nLength) = readTagAndLength()
        require(nTag == 0x02) { "Expected INTEGER tag for modulus" }
        val modulus = der.copyOfRange(offset, offset + nLength)
        offset += nLength

        // Remove leading zero byte if present (used to indicate positive number)
        val modulusStripped = if (modulus[0] == 0.toByte()) modulus.copyOfRange(1, modulus.size) else modulus

        // Read exponent INTEGER
        val (eTag, eLength) = readTagAndLength()
        require(eTag == 0x02) { "Expected INTEGER tag for exponent" }
        val exponent = der.copyOfRange(offset, offset + eLength)

        // Remove leading zero byte if present
        val exponentStripped = if (exponent[0] == 0.toByte()) exponent.copyOfRange(1, exponent.size) else exponent

        return RsaComponents(modulusStripped, exponentStripped)
    }
}
