package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.BinarySize
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.materials.key.Key
import dev.whyoleg.cryptography.materials.key.KeyDecoder
import dev.whyoleg.cryptography.materials.key.KeyFormat
import dev.whyoleg.cryptography.operations.SecretDerivation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * A secure 512-bit cryptographic key basis for deriving application-specific keys.
 *
 * This class provides a foundation for cryptographic operations like signing and encryption
 * by storing a single master secret and deriving variant-specific keys from it using HMAC-SHA512.
 *
 * The key derivation approach ensures that:
 * - Each variant produces a cryptographically independent key
 * - The master secret remains constant across application restarts
 * - Multiple encryption/signing contexts can share the same basis safely
 *
 * **Usage Example:**
 * ```kotlin
 * val basis = SecretBasis() // Generate new random basis
 * val cipher = basis.cipher("user-tokens") // Derive cipher for user tokens
 * val signer = basis.signer("session-auth") // Derive signer for sessions
 * ```
 *
 * @property string The Base64-encoded representation of the 512-bit secret key
 */
@Serializable(SecretBasis.Serializer::class)
public data class SecretBasis(public val string: String) {
    public companion object {
        /** The bit length of the secret key (512 bits for HMAC-SHA512) */
        public const val BITS: Int = 512

        /** The byte length of the secret key (64 bytes) */
        public const val BYTES: Int = BITS / 8

        /** Expected character count for Base64-encoded representation */
        public const val BASE64_CHARS: Int = 66
    }

    /**
     * Creates a new SecretBasis with cryptographically random bytes.
     *
     * This constructor generates a new random 512-bit key suitable for use as
     * a master secret. The generated key is immediately Base64-encoded for storage.
     */
    public constructor() : this(
        CryptographyProvider.Default.get(HMAC)
            .keyGenerator(SHA512)
            .generateKeyBlocking()
            .encodeToByteArrayBlocking(HMAC.Key.Format.RAW)
            .let(Base64::encode)
    )

    /**
     * The raw byte array representation of this SecretBasis.
     *
     * Decodes the Base64 string and extracts exactly [BYTES] (64) bytes.
     * This property is not serialized and is recalculated on deserialization.
     */
    @Transient
    public val bytes: ByteArray = Base64.decode(string).sliceArray(0 until BYTES)

    init {
        require(bytes.size == BYTES) { "Expected $BYTES bytes, got ${bytes.size}" }
    }

    @Transient
    @Volatile
    private var hmac: HMAC.Key? = null

    @Transient
    private val hmacLock = Any()
    @Transient
    private val hmacMutex = Mutex() // I'm not sure if this mutex is necessary, but I'm including it because I do not want to deal with a subtle concurrency bug in the future.

    /**
     * Lazily initializes and returns the HMAC-SHA512 key for this [SecretBasis].
     *
     * The key is cached after first access to avoid repeated deserialization.
     * This is the suspending version for use in coroutine contexts.
     *
     * **Thread Safety**: The cache is thread-safe. Concurrent calls will only decode
     * the key once, with other threads waiting for the result.
     *
     * @return The HMAC-SHA512 cryptographic key
     */
    public suspend fun key(): HMAC.Key = hmac ?: hmacMutex.withLock {
        hmac ?: CryptographyProvider.Default.get(HMAC)
            .keyDecoder(SHA512)
            .decodeFromByteArray(HMAC.Key.Format.RAW, bytes)
            .also { hmac = it }
    }

    /**
     * Lazily initializes and returns the HMAC-SHA512 key for this [SecretBasis].
     *
     * The key is cached after first access to avoid repeated deserialization.
     * This is the blocking version for use in non-coroutine contexts.
     *
     * **Thread Safety**: The cache is thread-safe. Concurrent calls will only decode
     * the key once, with other threads waiting for the result.
     *
     * @return The HMAC-SHA512 cryptographic key
     */
    public fun keyBlocking(): HMAC.Key = hmac ?: synchronized(hmacLock) {
        hmac ?: CryptographyProvider.Default.get(HMAC)
            .keyDecoder(SHA512)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, bytes)
            .also { hmac = it }
    }

    private fun expand(size: BinarySize): SecretDerivation {
        return CryptographyProvider.Default
            .get(HKDF)
            .secretDerivation(
                SHA512,
                size,
                salt = null as ByteArray?,
                info = null
            )
    }

    /**
     * Derives a cryptographic key from this [SecretBasis] using HMAC-SHA512.
     *
     * This function uses HMAC-SHA512 to derive a new key from the master secret.
     * Each unique input produces a cryptographically independent output key.
     *
     * @param key The derivation input (variant identifier) as bytes
     * @param size Optional target size for the derived key. If specified, the output is resized.
     * @return The derived key as a byte array
     */
    public suspend fun derive(
        key: ByteArray,
        size: BinarySize? = null
    ): ByteArray = key()
        .signatureGenerator()
        .generateSignature(key)
        .let {
            if (size == null) it
            else expand(size).deriveSecretToByteArray(it)
        }

    /**
     * Derives a cryptographic key from this [SecretBasis] using HMAC-SHA512 (blocking version).
     *
     * This function uses HMAC-SHA512 to derive a new key from the master secret.
     * Each unique input produces a cryptographically independent output key.
     *
     * @param key The derivation input (variant identifier) as bytes
     * @param size Optional target size for the derived key. If specified, the output is resized.
     * @return The derived key as a byte array
     */
    public fun deriveBlocking(
        key: ByteArray,
        size: BinarySize? = null
    ): ByteArray = keyBlocking()
        .signatureGenerator()
        .generateSignatureBlocking(key)
        .let {
            if (size == null) it
            else expand(size).deriveSecretToByteArrayBlocking(it)
        }

    /**
     * Derives a cryptographic key from this [SecretBasis] using a string variant identifier.
     *
     * This is a convenience wrapper around [derive] that accepts a string and encodes it to UTF-8 bytes.
     * Common usage pattern: `derive("user-sessions")` or `derive("api-tokens-v2")`.
     *
     * @param key The derivation variant identifier as a string
     * @param size Optional target size for the derived key
     * @return The derived key as a byte array
     */
    public suspend fun derive(key: String, size: BinarySize? = null): ByteArray = derive(key.encodeToByteArray(), size)

    /**
     * Derives a cryptographic key from this [SecretBasis] using a string variant identifier (blocking version).
     *
     * This is a convenience wrapper around [deriveBlocking] that accepts a string and encodes it to UTF-8 bytes.
     * Common usage pattern: `deriveBlocking("user-sessions")` or `deriveBlocking("api-tokens-v2")`.
     *
     * @param key The derivation variant identifier as a string
     * @param size Optional target size for the derived key
     * @return The derived key as a byte array
     */
    public fun deriveBlocking(key: String, size: BinarySize? = null): ByteArray = deriveBlocking(key.encodeToByteArray(), size)


    /**
     * Derives a typed cryptographic key from this [SecretBasis] using the provided variant.
     *
     * This is a higher-level function that derives raw bytes using HMAC-SHA512 and then
     * decodes them into a specific key type (e.g., AES.GCM.Key, HMAC.Key).
     *
     * @param KD The key decoder type
     * @param KF The key format type
     * @param K The resulting key type
     * @param decoder The key decoder for the target algorithm
     * @param format The format for key encoding (typically RAW)
     * @param variant The derivation variant identifier
     * @param size Optional target size for the derived key material
     * @return A typed cryptographic key ready for use
     */
    public suspend fun <KD : KeyDecoder<KF, K>, KF : KeyFormat, K : Key> deriveKey(
        decoder: KD,
        format: KF,
        variant: String,
        size: BinarySize? = null
    ): K = decoder.decodeFromByteArray(
        format,
        derive(variant, size)
    )

    /**
     * Derives a typed cryptographic key from this [SecretBasis] using the provided variant (blocking version).
     *
     * This is a higher-level function that derives raw bytes using HMAC-SHA512 and then
     * decodes them into a specific key type (e.g., AES.GCM.Key, HMAC.Key).
     *
     * @param KD The key decoder type
     * @param KF The key format type
     * @param K The resulting key type
     * @param decoder The key decoder for the target algorithm
     * @param format The format for key encoding (typically RAW)
     * @param variant The derivation variant identifier
     * @param size Optional target size for the derived key material
     * @return A typed cryptographic key ready for use
     */
    public fun <KD : KeyDecoder<KF, K>, KF : KeyFormat, K : Key> deriveKeyBlocking(
        decoder: KD,
        format: KF,
        variant: String,
        size: BinarySize? = null
    ): K = decoder.decodeFromByteArrayBlocking(format, deriveBlocking(variant, size))



    private object Serializer : KSerializer<SecretBasis> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.lightningkite.lightningserver.encryption.SecretBasis", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): SecretBasis = SecretBasis(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: SecretBasis) { encoder.encodeString(value.string) }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a companion factory method: `SecretBasis.fromBytes(bytes: ByteArray)` for users who
 *    want to construct from raw bytes instead of Base64.
 */