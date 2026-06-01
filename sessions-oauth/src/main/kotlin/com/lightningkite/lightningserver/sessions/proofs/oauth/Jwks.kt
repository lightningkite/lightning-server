package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.token.JwtSignatureException
import com.lightningkite.services.http.client
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import dev.whyoleg.cryptography.algorithms.SHA256 as CryptoSHA256

/**
 * A JSON Web Key Set fetcher with in-memory caching and key-rotation handling.
 *
 * Each [Jwks] instance corresponds to one IdP's JWKS URL. Public keys are fetched lazily on
 * first use and cached for [cacheFor]. If a request comes in for a `kid` not present in the
 * cache, the cache is refreshed exactly once before failing — this transparently handles
 * routine key rotation without unbounded refresh loops.
 *
 * @param url Absolute HTTPS URL of the IdP's JWKS document.
 * @param cacheFor How long fetched keys are reused before a forced refresh.
 */
public open class Jwks(
    public val url: String,
    public val cacheFor: Duration = 24.hours,
) {
    private val mutex = Mutex()
    private var cached: Map<String, Signer>? = null
    private var cachedAt: Instant? = null

    @Serializable
    private data class JwksDocument(val keys: List<JwksKey>)

    @Serializable
    private data class JwksKey(
        val kty: String,
        val kid: String,
        val use: String? = null,
        val alg: String? = null,
        val n: String? = null,
        val e: String? = null,
    )

    /**
     * Returns a verification-only [Signer] for the given JWT `kid`.
     *
     * If [kid] is not present in the current cache, the cache is refreshed once and tried again.
     * Throws [JwtSignatureException] if the key is still not found after refresh, or if the
     * matching key uses an unsupported algorithm.
     */
    context(runtime: ServerRuntime)
    public open suspend fun signer(kid: String): Signer {
        currentValid()?.get(kid)?.let { return it }
        return refresh()[kid] ?: throw JwtSignatureException("No matching JWKS key for kid '$kid'")
    }

    context(runtime: ServerRuntime)
    private suspend fun currentValid(): Map<String, Signer>? = mutex.withLock {
        val keys = cached ?: return@withLock null
        val at = cachedAt ?: return@withLock null
        if (now() < at + cacheFor) keys else null
    }

    context(runtime: ServerRuntime)
    private suspend fun refresh(): Map<String, Signer> = mutex.withLock {
        val doc = client.get(url).body<JwksDocument>()
        val signers = doc.keys.mapNotNull { key -> key.toSigner()?.let { key.kid to it } }.toMap()
        cached = signers
        cachedAt = now()
        signers
    }

    private fun JwksKey.toSigner(): Signer? {
        if (kty != "RSA") return null
        if (n == null || e == null) return null
        return buildRsaVerifier(n, e)
    }
}

/**
 * Builds a verification-only [Signer] from base64url-encoded RSA modulus and exponent.
 *
 * Reused by both [Jwks] (for OIDC providers) and Apple's hand-written keys path.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun buildRsaVerifier(nBase64Url: String, eBase64Url: String): Signer {
    val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    val nBytes = decoder.decode(nBase64Url)
    val eBytes = decoder.decode(eBase64Url)

    val crypto = CryptographyProvider.Default
    val rsaPkcs1 = crypto.get(RSA.PKCS1)

    val publicKey = rsaPkcs1.publicKeyDecoder(CryptoSHA256).decodeFromByteArrayBlocking(
        RSA.PublicKey.Format.DER,
        rsaPublicKeyToSpkiDer(nBytes, eBytes)
    )

    return object : Signer {
        override val generator get() = throw UnsupportedOperationException("Verification-only signer")
        override val verifier = publicKey.signatureVerifier()
        override val name: String = "RS256"
    }
}

/**
 * Encodes an RSA public key as SPKI/X.509 DER.
 *
 * Format: SEQUENCE { SEQUENCE { OID rsaEncryption, NULL }, BIT STRING { SEQUENCE { INTEGER n, INTEGER e } } }
 */
private fun rsaPublicKeyToSpkiDer(n: ByteArray, e: ByteArray): ByteArray {
    val rsaOid = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)

    fun derLength(length: Int): ByteArray = when {
        length < 128 -> byteArrayOf(length.toByte())
        length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte())
    }

    fun derInteger(value: ByteArray): ByteArray {
        val needsLeadingZero = value.isNotEmpty() && (value[0].toInt() and 0x80) != 0
        val content = if (needsLeadingZero) byteArrayOf(0) + value else value
        return byteArrayOf(0x02) + derLength(content.size) + content
    }

    val nEncoded = derInteger(n)
    val eEncoded = derInteger(e)
    val keySequence = byteArrayOf(0x30) + derLength(nEncoded.size + eEncoded.size) + nEncoded + eEncoded
    val bitString = byteArrayOf(0x03) + derLength(keySequence.size + 1) + byteArrayOf(0x00) + keySequence
    val algorithm = byteArrayOf(0x30) + derLength(rsaOid.size + 4) +
            byteArrayOf(0x06, rsaOid.size.toByte()) + rsaOid +
            byteArrayOf(0x05, 0x00)

    val fullSequence = algorithm + bitString
    return byteArrayOf(0x30) + derLength(fullSequence.size) + fullSequence
}
