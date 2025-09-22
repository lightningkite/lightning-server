package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.BinarySize
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.materials.key.Key
import dev.whyoleg.cryptography.materials.key.KeyDecoder
import dev.whyoleg.cryptography.materials.key.KeyFormat
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
 * A secure basis for cryptographic operations.
 * This class provides a foundation for cryptographic operations like signing and encryption.
 */
@Serializable(SecretBasis.Serializer::class)
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

    private fun ByteArray.padToSize(size: BinarySize): ByteArray {
        if (this.size <= size.inBytes) return sliceArray(0 until size.inBytes)

        var remaining = this.size - size.inBytes
        val padded = ByteArray(remaining)

        var offset = 0
        while (remaining > 0) {
            val copy =
                if (remaining < this.size) sliceArray(0 until remaining)
                else this

            copy.copyInto(padded, offset)

            remaining -= copy.size
            offset += copy.size
        }

        return padded
    }

    public suspend fun derive(
        key: ByteArray,
        size: BinarySize? = null
    ): ByteArray = key()
        .signatureGenerator()
        .generateSignature(key)
        .let { if (size == null) it else it.padToSize(size) }

    public fun deriveBlocking(
        key: ByteArray,
        size: BinarySize? = null
    ): ByteArray = keyBlocking()
        .signatureGenerator()
        .generateSignatureBlocking(key)
        .let { if (size == null) it else it.padToSize(size) }

    /**
     * Derives a key from this [SecretBasis] using the provided key.
     * This implementation uses HMAC-SHA512.
     */
    public suspend fun derive(key: String, size: BinarySize? = null): ByteArray = derive(key.encodeToByteArray(), size)

    /**
     * Derives a key from this [SecretBasis] using the provided key.
     * This implementation uses HMAC-SHA512.
     */
    public fun deriveBlocking(key: String, size: BinarySize? = null): ByteArray = deriveBlocking(key.encodeToByteArray(), size)


    /**
     * Derives a `whyoleg.cryptography.Key` from this [SecretBasis] using the provided variant.
     * This implementation uses HMAC-SHA512.
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
     * Derives a `whyoleg.cryptography.Key` from this [SecretBasis] using the provided variant.
     * This implementation uses HMAC-SHA512.
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