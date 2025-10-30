@file:Suppress("FunctionName")

/**
 * Extension functions for deriving cipher keys from SecretBasis.
 *
 * This file provides convenient methods to derive AES cipher keys in various modes
 * (CBC, CTR, GCM) from a SecretBasis master secret. Each variant string produces
 * a cryptographically independent cipher key.
 */
package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.map
import com.lightningkite.lightningserver.definition.mapSuspending
import dev.whyoleg.cryptography.BinarySize
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.operations.Cipher
import dev.whyoleg.cryptography.operations.Decryptor
import dev.whyoleg.cryptography.operations.Encryptor


/**
 * Derives an AES-GCM cipher for the specified variant.
 *
 * This is the default and recommended cipher method, using AES-GCM with 256-bit keys
 * and 128-bit authentication tags. GCM mode provides both confidentiality and authenticity.
 *
 * @param variant The variant identifier for key derivation (e.g., "user-tokens", "session-data")
 * @return A Cipher capable of encryption and decryption
 */
public suspend fun SecretBasis.cipher(variant: String): Cipher = AES_GCM(variant).cipher()

/**
 * Derives an AES-GCM cipher for the specified variant from a Runtime context.
 *
 * The cipher is cached to avoid repeated key derivation.
 *
 * @param variant The variant identifier for key derivation
 * @return A RuntimeDeferred containing the cipher
 */
public fun Runtime<SecretBasis>.cipher(variant: String): RuntimeDeferred<Cipher> = RuntimeDeferred.Cached { this().cipher(variant) }

/**
 * Derives an AES-GCM cipher for the specified variant (blocking version).
 *
 * Uses AES-GCM with 256-bit keys and 128-bit authentication tags.
 *
 * @param variant The variant identifier for key derivation
 * @return A Cipher capable of encryption and decryption
 */
public fun SecretBasis.cipherBlocking(variant: String): Cipher = AES_GCM_Blocking(variant).cipher()

/**
 * Derives an AES-GCM cipher for the specified variant from a Runtime context (blocking version).
 *
 * The cipher is cached to avoid repeated key derivation.
 *
 * @param variant The variant identifier for key derivation
 * @return A Runtime containing the cipher
 */
public fun Runtime<SecretBasis>.cipherBlocking(variant: String): Runtime<Cipher> = Runtime.Cached { this().cipherBlocking(variant) }


/**
 * Enumeration of AES key sizes.
 *
 * AES supports three key sizes: 128, 192, and 256 bits.
 * Larger keys provide stronger security but may have performance implications.
 *
 * **Recommendation**: Use B256 (256-bit keys) for maximum security unless
 * you have specific performance requirements.
 */
@Suppress("ClassName")
public enum class AES_KeySize(public val size: BinarySize) {
    /** 128-bit AES key */
    B128(AES.Key.Size.B128),

    /** 192-bit AES key */
    B192(AES.Key.Size.B192),

    /** 256-bit AES key (recommended) */
    B256(AES.Key.Size.B256)
}

/**
 * Derives an AES-CBC mode key from this SecretBasis.
 *
 * **Warning**: CBC mode does not provide authentication. It only provides confidentiality.
 * Consider using AES-GCM instead for authenticated encryption.
 *
 * @param variant The variant identifier for key derivation
 * @param size The key size (defaults to 256-bit)
 * @return An AES-CBC key
 */
public suspend fun SecretBasis.AES_CBC(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CBC.Key = deriveKey(
    CryptographyProvider.Default.get(AES.CBC).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

/**
 * Derives an AES-CBC mode key from this SecretBasis (blocking version).
 *
 * **Warning**: CBC mode does not provide authentication. It only provides confidentiality.
 * Consider using AES-GCM instead for authenticated encryption.
 *
 * @param variant The variant identifier for key derivation
 * @param size The key size (defaults to 256-bit)
 * @return An AES-CBC key
 */
public fun SecretBasis.AES_CBC_Blocking(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CBC.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.CBC).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

/**
 * Derives an AES-CTR mode key from this SecretBasis.
 *
 * **Warning**: CTR mode does not provide authentication. It only provides confidentiality.
 * Consider using AES-GCM instead for authenticated encryption.
 *
 * @param variant The variant identifier for key derivation
 * @param size The key size (defaults to 256-bit)
 * @return An AES-CTR key
 */
public suspend fun SecretBasis.AES_CTR(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CTR.Key = deriveKey(
    CryptographyProvider.Default.get(AES.CTR).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

/**
 * Derives an AES-CTR mode key from this SecretBasis (blocking version).
 *
 * **Warning**: CTR mode does not provide authentication. It only provides confidentiality.
 * Consider using AES-GCM instead for authenticated encryption.
 *
 * @param variant The variant identifier for key derivation
 * @param size The key size (defaults to 256-bit)
 * @return An AES-CTR key
 */
public fun SecretBasis.AES_CTR_Blocking(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CTR.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.CTR).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

/**
 * Derives an AES-GCM mode key from this SecretBasis.
 *
 * **Recommended**: GCM mode provides both encryption and authentication (AEAD).
 * This is the preferred mode for most use cases as it protects against tampering.
 *
 * @param variant The variant identifier for key derivation
 * @param size The key size (defaults to 256-bit)
 * @return An AES-GCM key
 */
public suspend fun SecretBasis.AES_GCM(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.GCM.Key = deriveKey(
    CryptographyProvider.Default.get(AES.GCM).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

/**
 * Derives an AES-GCM mode key from this SecretBasis (blocking version).
 *
 * **Recommended**: GCM mode provides both encryption and authentication (AEAD).
 * This is the preferred mode for most use cases as it protects against tampering.
 *
 * @param variant The variant identifier for key derivation
 * @param size The key size (defaults to 256-bit)
 * @return An AES-GCM key
 */
public fun SecretBasis.AES_GCM_Blocking(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.GCM.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.GCM).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

/**
 * Internal implementation of Cipher that delegates to separate encryptor and decryptor instances.
 *
 * This allows composing a Cipher from independently created encryption and decryption components.
 */
@OptIn(CryptographyProviderApi::class)
private data class CipherFromParts(
    val encryptor: Encryptor,
    val decryptor: Decryptor
): Cipher, Encryptor by encryptor, Decryptor by decryptor

/**
 * Creates a Cipher from separate Encryptor and Decryptor instances.
 *
 * This is useful when you need to construct a cipher from independently created
 * encryption and decryption components.
 *
 * @param encryptor The encryptor to use
 * @param decryptor The decryptor to use
 * @return A Cipher that delegates to the provided components
 */
public fun Cipher(encryptor: Encryptor, decryptor: Decryptor): Cipher = CipherFromParts(encryptor, decryptor)

/*
 * TODO: API Recommendations
 *
 * 1. Consider consistency in naming: The convention uses both `cipher()` and `AES_GCM()` patterns.
 *    It might be clearer to have `cipher()` always use GCM and require explicit mode selection otherwise.
 *
 * 2. Add ChaCha20-Poly1305 support: This is a modern AEAD cipher that may be preferable to AES-GCM
 *    in some contexts (especially non-hardware-accelerated environments).
 */