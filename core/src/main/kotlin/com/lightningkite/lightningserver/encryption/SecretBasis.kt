package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.materials.key.Key
import dev.whyoleg.cryptography.materials.key.KeyDecoder
import dev.whyoleg.cryptography.materials.key.KeyFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.io.encoding.Base64

/**
 * A secure basis for cryptographic operations.
 * This class provides a foundation for cryptographic operations like hashing and encryption.
 *
 * Note: This implementation currently uses JVM-specific cryptography libraries.
 * For multiplatform support, this should be refactored to use the dev.whyoleg.cryptography
 * libraries that are already included in the project dependencies.
 *
 * TODO: Implement multiplatform support using dev.whyoleg.cryptography:
 * 1. Create expect/actual declarations for platform-specific implementations
 * 2. Implement JVM version using either JVM crypto or dev.whyoleg.cryptography
 * 3. Implement JS/Native versions using dev.whyoleg.cryptography
 */
@Serializable
public data class SecretBasis(public val string: String) {
    public companion object {
        public const val BITS: Int = 512
        public const val BYTES: Int = BITS / 8
        public const val BASE64_CHARS: Int = 66
    }

    /**
     * Creates a new SecretBasis with random bytes.
     */
    public constructor() : this(
        CryptographyProvider.Default.get(HMAC)
            .keyGenerator(SHA512)
            .generateKeyBlocking()
            .encodeToByteArrayBlocking(HMAC.Key.Format.RAW)
            .let(Base64::encode)
    )

    /**
     * Gets the bytes representation of this SecretBasis.
     */
    @Transient
    public val bytes: ByteArray = Base64.decode(string).sliceArray(0 until BYTES)

    @Transient
    private var hmac: HMAC.Key? = null

    /**
     * The HMAC-SHA512 key for this [SecretBasis]
     * */
    public suspend fun key(): HMAC.Key = hmac ?: CryptographyProvider.Default.get(HMAC)
        .keyDecoder(SHA512)
        .decodeFromByteArray(HMAC.Key.Format.RAW, bytes)
        .also { hmac = it }

    /**
     * The HMAC-SHA512 key for this [SecretBasis]
     * */
    public fun keyBlocking(): HMAC.Key = hmac ?: CryptographyProvider.Default.get(HMAC)
        .keyDecoder(SHA512)
        .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, bytes)
        .also { hmac = it }


    /**
     * Derives a key from this [SecretBasis] using the provided key.
     * This implementation uses HMAC-SHA512.
     */
    public suspend fun derive(key: String): ByteArray = key()
        .signatureGenerator()
        .generateSignature(key.encodeToByteArray())

    /**
     * Derives a key from this [SecretBasis] using the provided key.
     * This implementation uses HMAC-SHA512.
     */
    public fun deriveBlocking(key: String): ByteArray = keyBlocking()
        .signatureGenerator()
        .generateSignatureBlocking(key.encodeToByteArray())


    /**
     * Derives a `whyoleg.cryptography.Key` from this [SecretBasis] using the provided variant.
     * This implementation uses HMAC-SHA512.
     */
    public suspend fun <KD : KeyDecoder<KF, K>, KF : KeyFormat, K : Key> deriveKey(
        decoder: KD,
        format: KF,
        variant: String
    ): K = decoder.decodeFromByteArray(format, derive(variant))

    /**
     * Derives a `whyoleg.cryptography.Key` from this [SecretBasis] using the provided variant.
     * This implementation uses HMAC-SHA512.
     */
    public fun <KD : KeyDecoder<KF, K>, KF : KeyFormat, K : Key> deriveKeyBlocking(
        decoder: KD,
        format: KF,
        variant: String
    ): K = decoder.decodeFromByteArrayBlocking(format, deriveBlocking(variant))
}