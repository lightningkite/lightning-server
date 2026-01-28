@file:Suppress("FunctionName", "ClassName")

/**
 * Cryptographic signature interfaces and implementations.
 *
 * This file defines the [Signer] interface and various implementations for
 * different signature algorithms (HMAC, CMAC, ECDSA, RSA). Signers provide
 * both signature generation and verification capabilities.
 */
package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.operations.SignatureGenerator
import dev.whyoleg.cryptography.operations.SignatureVerifier
import kotlin.io.encoding.Base64

/**
 * Interface for cryptographic signature generation and verification.
 *
 * A Signer provides both a signature generator (for creating signatures) and
 * a signature verifier (for validating signatures). Different implementations
 * support symmetric (HMAC, CMAC) and asymmetric (ECDSA, RSA) signing algorithms.
 *
 * The [name] property typically follows JWT algorithm naming conventions
 * (e.g., "HS256", "ES256", "RS256").
 */
public interface Signer {
    /** The signature generator for creating signatures */
    public val generator: SignatureGenerator

    /** The signature verifier for validating signatures */
    public val verifier: SignatureVerifier

    /** The algorithm name (typically follows JWT naming, e.g., "HS256", "RS256") */
    public val name: String

    /**
     * HMAC-based signer using symmetric keys.
     *
     * HMAC signatures use the same key for both signing and verification.
     * This is suitable for scenarios where both parties share a secret.
     *
     * @property key The HMAC key
     * @property name The algorithm name (e.g., "HS256", "HS384", "HS512")
     */
    public data class HMAC(public val key: dev.whyoleg.cryptography.algorithms.HMAC.Key, override val name: String) : Signer {
        override val generator: SignatureGenerator get() = key.signatureGenerator()
        override val verifier: SignatureVerifier get() = key.signatureVerifier()
    }

    /**
     * CMAC-based signer using AES keys.
     *
     * CMAC (Cipher-based MAC) uses block cipher (AES) for message authentication.
     *
     * @property key The AES-CMAC key
     * @property name The algorithm name
     */
    public data class CMAC(public val key: AES.CMAC.Key, override val name: String) : Signer {
        override val generator: SignatureGenerator get() = key.signatureGenerator()
        override val verifier: SignatureVerifier get() = key.signatureVerifier()
    }

    /**
     * ECDSA-based signer using elliptic curve asymmetric keys.
     *
     * ECDSA signatures use a private key for signing and a public key for verification.
     * This is suitable for scenarios where signatures need to be verified by parties
     * who don't have access to the signing key (e.g., JWT tokens).
     *
     * @property keyPair The ECDSA key pair (private key for signing, public key for verification)
     * @property digest The hash algorithm to use
     * @property format The signature format (RAW or DER)
     * @property name The algorithm name (e.g., "ES256", "ES384", "ES512")
     */
    public data class ECDSA(
        public val keyPair: dev.whyoleg.cryptography.algorithms.ECDSA.KeyPair,
        public val digest: CryptographyAlgorithmId<Digest>,
        public val format: dev.whyoleg.cryptography.algorithms.ECDSA.SignatureFormat,
        override val name: String
    ) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator(digest, format)
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier(digest, format)
    }

    /**
     * RSA-PSS signer using RSA keys with PSS padding.
     *
     * RSA-PSS is a more secure RSA signature scheme compared to PKCS1.
     * Helper functions for this are not available because the library RSA classes
     * are internal, so you must specify the algorithm name manually.
     *
     * @property keyPair The RSA key pair
     * @property name The algorithm name (e.g., "PS256", "PS384", "PS512")
     */
    public data class RSA_PSS(public val keyPair: RSA.PSS.KeyPair, override val name: String /*Example: PS256*/) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator()
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier()
    }

    /**
     * RSA-PKCS1 signer using RSA keys with PKCS#1 v1.5 padding.
     *
     * **Note**: PKCS1 is less secure than PSS and should only be used for compatibility.
     * Helper functions for this are not available because the library RSA classes
     * are internal, so you must specify the algorithm name manually.
     *
     * @property keyPair The RSA key pair
     * @property name The algorithm name (e.g., "RS256", "RS384", "RS512")
     */
    public data class RSA_PKCS1(public val keyPair: RSA.PKCS1.KeyPair, override val name: String /*Example: RS256*/) : Signer {
        override val generator: SignatureGenerator get() = keyPair.privateKey.signatureGenerator()
        override val verifier: SignatureVerifier get() = keyPair.publicKey.signatureVerifier()
    }
}

/**
 * Signs a byte array and returns the signature.
 *
 * @receiver The signer to use
 * @param bytes The data to sign
 * @return The signature as a byte array
 */
public suspend fun Signer.sign(bytes: ByteArray): ByteArray = generator.generateSignature(bytes)

/**
 * Verifies a signature against the provided data.
 *
 * @receiver The signer to use
 * @param bytes The original data
 * @param signature The signature to verify
 * @return `true` if the signature is valid, `false` otherwise
 */
public suspend fun Signer.verify(bytes: ByteArray, signature: ByteArray): Boolean = verifier.tryVerifySignature(bytes, signature)

/**
 * Signs a byte array and returns the signature (blocking version).
 *
 * @receiver The signer to use
 * @param bytes The data to sign
 * @return The signature as a byte array
 */
public fun Signer.signBlocking(bytes: ByteArray): ByteArray = generator.generateSignatureBlocking(bytes)

/**
 * Verifies a signature against the provided data (blocking version).
 *
 * @receiver The signer to use
 * @param bytes The original data
 * @param signature The signature to verify
 * @return `true` if the signature is valid, `false` otherwise
 */
public fun Signer.verifyBlocking(bytes: ByteArray, signature: ByteArray): Boolean = verifier.tryVerifySignatureBlocking(bytes, signature)

/**
 * Signs a string and returns a Base64-encoded signature.
 *
 * This is a convenience function that encodes the string to UTF-8 bytes,
 * signs it, and returns the signature as a Base64 string.
 *
 * @receiver The signer to use
 * @param string The string to sign
 * @return The signature as a Base64-encoded string
 */
public suspend fun Signer.sign(string: String): String = Base64.encode(sign(string.encodeToByteArray()))

/**
 * Verifies a Base64-encoded signature against a string.
 *
 * This is a convenience function that encodes the string to UTF-8 bytes
 * and verifies the Base64-decoded signature.
 *
 * @receiver The signer to use
 * @param string The original string
 * @param signature The Base64-encoded signature
 * @return `true` if the signature is valid, `false` otherwise
 */
public suspend fun Signer.verify(string: String, signature: String): Boolean = verify(string.encodeToByteArray(), signature.encodeToByteArray())

public fun Signer.signBlocking(string: String): String = Base64.encode(signBlocking(string.encodeToByteArray()))

public fun Signer.verifyBlocking(string: String, signature: String): Boolean = verifyBlocking(string.encodeToByteArray(), signature.encodeToByteArray())

/**
 * Creates an ES256 (ECDSA with SHA-256) signer from this key pair.
 *
 * @param format The signature format (defaults to RAW)
 * @return A Signer configured for ES256
 */
public fun ECDSA.KeyPair.ES256(format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW): Signer = Signer.ECDSA(this, SHA256, format, "ES256")

/**
 * Creates an ES384 (ECDSA with SHA-384) signer from this key pair.
 *
 * @param format The signature format (defaults to RAW)
 * @return A Signer configured for ES384
 */
public fun ECDSA.KeyPair.ES384(format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW): Signer = Signer.ECDSA(this, SHA384, format, "ES384")

/**
 * Creates an ES512 (ECDSA with SHA-512) signer from this key pair.
 *
 * @param format The signature format (defaults to RAW)
 * @return A Signer configured for ES512
 */
public fun ECDSA.KeyPair.ES512(format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW): Signer = Signer.ECDSA(this, SHA512, format, "ES512")

/*
 * TODO: API Recommendations
 *
 * 2. Consider adding helper methods for RSA signers similar to the ECDSA helpers (ES256, ES384, ES512).
 *
 * 3. Error handling: Consider making verify() return a Result type or throwing exceptions on signature
 *    format errors to distinguish between invalid signatures and malformed data.
 */
