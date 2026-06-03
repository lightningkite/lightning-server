@file:OptIn(ExperimentalEncodingApi::class)

package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.encryption.Signer
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.RSA
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Converts Lightning Server [Signer]s into JWK (JSON Web Key) form so the provider can publish its
 * public keys at the JWKS endpoint, where relying parties fetch them to verify ID token signatures.
 */
public object JwksUtils {

    /**
     * Converts an RSA signer to a [JsonWebKey].
     *
     * @param signer An RSA signer ([Signer.RSA_PKCS1] or [Signer.RSA_PSS])
     * @param keyId The key id (`kid`); must match the `kid` placed in signed JWT headers
     */
    public fun toJwk(signer: Signer, keyId: String = Uuid.random().toString()): JsonWebKey {
        return when (signer) {
            is Signer.RSA_PKCS1 -> rsaToJwk(signer.keyPair.publicKey, signer.name, keyId)
            is Signer.RSA_PSS -> rsaToJwk(signer.keyPair.publicKey, signer.name, keyId)
            is Signer.ECDSA -> ecToJwk(signer.keyPair.publicKey, signer.name, keyId)
            else -> throw IllegalArgumentException("Only RSA and ECDSA signers are supported for JWKS. Got: ${signer::class.simpleName}")
        }
    }

    private fun ecToJwk(publicKey: EC.PublicKey, alg: String, keyId: String): JsonWebKey {
        // Uncompressed point encoding: 0x04 || X || Y, each coordinate `coordLen` bytes.
        val raw = publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed)
        require(raw.isNotEmpty() && raw[0] == 0x04.toByte()) { "Expected uncompressed EC point" }
        val coordLen = (raw.size - 1) / 2
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val crv = when (alg) {
            "ES256" -> "P-256"
            "ES384" -> "P-384"
            "ES512" -> "P-521"
            else -> throw IllegalArgumentException("Unsupported EC algorithm: $alg")
        }
        return JsonWebKey(
            kty = "EC", use = "sig", kid = keyId, alg = alg, crv = crv,
            x = encoder.encode(raw.copyOfRange(1, 1 + coordLen)),
            y = encoder.encode(raw.copyOfRange(1 + coordLen, 1 + 2 * coordLen)),
        )
    }

    /** Wraps a single signer in a [JwksResponse]. */
    public fun toJwks(signer: Signer, keyId: String = "default"): JwksResponse =
        JwksResponse(keys = listOf(toJwk(signer, keyId)))

    /** Wraps multiple keyed signers in a [JwksResponse] (used during key rotation). */
    public fun toJwks(signers: Map<String, Signer>): JwksResponse =
        JwksResponse(keys = signers.map { (kid, signer) -> toJwk(signer, kid) })

    private fun rsaToJwk(publicKey: RSA.PublicKey, alg: String, keyId: String): JsonWebKey {
        val (n, e) = extractRsaPublicKeyComponents(publicKey)
        return JsonWebKey(kty = "RSA", use = "sig", kid = keyId, alg = alg, n = n, e = e)
    }

    /**
     * Extracts the RSA modulus (n) and exponent (e) from a public key, base64url-encoded.
     *
     * The key is exported in DER (SPKI) form and parsed to pull out the two INTEGER values.
     */
    private fun extractRsaPublicKeyComponents(publicKey: RSA.PublicKey): Pair<String, String> {
        val derBytes = publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
        try {
            val components = parseDerRsaPublicKey(derBytes)
            val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            return Pair(encoder.encode(components.first), encoder.encode(components.second))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse RSA public key DER encoding", e)
        }
    }

    /**
     * Minimal DER parser for an RSA public key (SPKI):
     * SEQUENCE { SEQUENCE { OID, NULL }, BIT STRING { SEQUENCE { INTEGER n, INTEGER e } } }
     */
    private fun parseDerRsaPublicKey(der: ByteArray): Pair<ByteArray, ByteArray> {
        var offset = 0

        fun readTagAndLength(): Pair<Int, Int> {
            val tag = der[offset++].toInt() and 0xFF
            var length = der[offset++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val numLengthBytes = length and 0x7F
                length = 0
                repeat(numLengthBytes) { length = (length shl 8) or (der[offset++].toInt() and 0xFF) }
            }
            return tag to length
        }

        val (outerTag, _) = readTagAndLength()
        require(outerTag == 0x30) { "Expected SEQUENCE tag (0x30), got ${outerTag.toString(16)}" }

        val (algTag, algLength) = readTagAndLength()
        require(algTag == 0x30) { "Expected SEQUENCE tag for algorithm" }
        offset += algLength  // skip algorithm identifier

        val (bitStringTag, _) = readTagAndLength()
        require(bitStringTag == 0x03) { "Expected BIT STRING tag (0x03)" }
        offset++  // skip unused-bits byte

        val (innerTag, _) = readTagAndLength()
        require(innerTag == 0x30) { "Expected SEQUENCE tag for key components" }

        val (nTag, nLength) = readTagAndLength()
        require(nTag == 0x02) { "Expected INTEGER tag for modulus" }
        val modulus = der.copyOfRange(offset, offset + nLength)
        offset += nLength
        val modulusStripped = if (modulus[0] == 0.toByte()) modulus.copyOfRange(1, modulus.size) else modulus

        val (eTag, eLength) = readTagAndLength()
        require(eTag == 0x02) { "Expected INTEGER tag for exponent" }
        val exponent = der.copyOfRange(offset, offset + eLength)
        val exponentStripped = if (exponent[0] == 0.toByte()) exponent.copyOfRange(1, exponent.size) else exponent

        return modulusStripped to exponentStripped
    }
}
