@file:Suppress("FunctionName")

/**
 * Extension functions for deriving signature keys from SecretBasis.
 *
 * This file provides convenient methods to derive HMAC-based signing keys
 * from a SecretBasis master secret. The signers support various hash algorithms
 * (SHA-256, SHA-384, SHA-512) and are suitable for creating JWT signatures and
 * other message authentication codes.
 */
package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.map
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.SHA512

/**
 * Derives a signer for the specified variant using HMAC-SHA512.
 *
 * This is the default and recommended signer method. HS512 provides strong
 * message authentication suitable for JWTs and other signed data.
 *
 * @param variant The variant identifier for key derivation (e.g., "jwt-tokens", "api-signatures")
 * @return A Signer capable of generating and verifying signatures
 */
public suspend fun SecretBasis.signer(variant: String): Signer = HS512(variant)

/**
 * Derives a signer for the specified variant from a Runtime context.
 *
 * Uses HMAC-SHA512. The signer is cached to avoid repeated key derivation.
 *
 * @param variant The variant identifier for key derivation
 * @return A RuntimeDeferred containing the signer
 */
public fun Runtime<SecretBasis>.signer(variant: String): RuntimeDeferred<Signer> =
    RuntimeDeferred.Cached { this().signer(variant) }

/**
 * Derives a signer for the specified variant using HMAC-SHA512 (blocking version).
 *
 * This is the blocking version for use in non-coroutine contexts.
 *
 * @param variant The variant identifier for key derivation
 * @return A Signer capable of generating and verifying signatures
 */
public fun SecretBasis.signerBlocking(variant: String): Signer = Signer.HMAC(HMAC_Blocking(variant, SHA512), "HS512")

/**
 * Derives a signer for the specified variant from a Runtime context (blocking version).
 *
 * Uses HMAC-SHA512. The signer is cached to avoid repeated key derivation.
 *
 * @param variant The variant identifier for key derivation
 * @return A Runtime containing the signer
 */
public fun Runtime<SecretBasis>.signerBlocking(variant: String): Runtime<Signer> = map { it.signerBlocking(variant) }

/**
 * Derives an HMAC-SHA256 signer (HS256 in JWT terminology).
 *
 * @param variant The variant identifier for key derivation
 * @return A Signer using HMAC-SHA256
 */
public suspend fun SecretBasis.HS256(variant: String): Signer = Signer.HMAC(HMAC(variant, SHA256), "HS256")

/**
 * Derives an HMAC-SHA384 signer (HS384 in JWT terminology).
 *
 * @param variant The variant identifier for key derivation
 * @return A Signer using HMAC-SHA384
 */
public suspend fun SecretBasis.HS384(variant: String): Signer = Signer.HMAC(HMAC(variant, SHA384), "HS384")

/**
 * Derives an HMAC-SHA512 signer (HS512 in JWT terminology).
 *
 * **Recommended**: This is the strongest HMAC variant and should be preferred
 * for new applications unless compatibility requires a different algorithm.
 *
 * @param variant The variant identifier for key derivation
 * @return A Signer using HMAC-SHA512
 */
public suspend fun SecretBasis.HS512(variant: String): Signer = Signer.HMAC(HMAC(variant, SHA512), "HS512")

/**
 * Derives an HMAC key with the specified digest algorithm.
 *
 * This is a lower-level function that returns the raw HMAC key rather than
 * a Signer wrapper. Use the HS256/HS384/HS512 functions for most use cases.
 *
 * @param variant The variant identifier for key derivation
 * @param digest The hash algorithm to use (defaults to SHA-512)
 * @return An HMAC key
 */
public suspend fun SecretBasis.HMAC(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
): HMAC.Key = deriveKey(
    CryptographyProvider.Default.get(HMAC).keyDecoder(digest),
    HMAC.Key.Format.RAW,
    variant
)

/**
 * Derives an HMAC key with the specified digest algorithm (blocking version).
 *
 * This is a lower-level function that returns the raw HMAC key rather than
 * a Signer wrapper. Use the blocking signer functions for most use cases.
 *
 * @param variant The variant identifier for key derivation
 * @param digest The hash algorithm to use (defaults to SHA-512)
 * @return An HMAC key
 */
public fun SecretBasis.HMAC_Blocking(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
): HMAC.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(HMAC).keyDecoder(digest),
    HMAC.Key.Format.RAW,
    variant
)

/*
 * TODO: API Recommendations
 *
 * 1. Add blocking versions: Consider adding HS256_Blocking, HS384_Blocking, HS512_Blocking
 *    for consistency with other APIs.
 *
 * 2. Consider EdDSA support: For asymmetric signing (public/private key pairs), EdDSA
 *    (Ed25519) would be a valuable addition for use cases requiring public key verification.
 */
