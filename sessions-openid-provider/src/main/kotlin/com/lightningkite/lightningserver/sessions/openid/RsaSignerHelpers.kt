@file:Suppress("FunctionName")

package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.encryption.Signer
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA

/**
 * Generates an RSA-PKCS1 key pair for RS256 signing (RSA with SHA-256).
 *
 * RS256 is the required minimum signing algorithm for OpenID Connect ID tokens.
 * This uses asymmetric cryptography where:
 * - Private key is used for signing (kept secret on server)
 * - Public key is used for verification (published via JWKS endpoint)
 *
 * @return A Signer using RS256
 */
public suspend fun generateRS256Signer(): Signer {
    val crypto = CryptographyProvider.Default
    val rsa = crypto.get(RSA.PKCS1)
    // RSA PKCS1 uses the same key pair for all hash algorithms (SHA256, SHA384, SHA512)
    // The hash algorithm is specified when creating the signer
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PKCS1(keyPair, "RS256")
}

/**
 * Generates an RSA-PKCS1 key pair for RS384 signing (RSA with SHA-384).
 *
 * @return A Signer using RS384
 */
public suspend fun generateRS384Signer(): Signer {
    val crypto = CryptographyProvider.Default
    val rsa = crypto.get(RSA.PKCS1)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PKCS1(keyPair, "RS384")
}

/**
 * Generates an RSA-PKCS1 key pair for RS512 signing (RSA with SHA-512).
 *
 * @return A Signer using RS512
 */
public suspend fun generateRS512Signer(): Signer {
    val crypto = CryptographyProvider.Default
    val rsa = crypto.get(RSA.PKCS1)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PKCS1(keyPair, "RS512")
}

/**
 * Generates an RSA-PSS key pair for PS256 signing (RSA-PSS with SHA-256).
 *
 * RSA-PSS is more secure than PKCS1 but not as widely supported.
 * Use this if clients support PS256.
 *
 * @return A Signer using PS256
 */
public suspend fun generatePS256Signer(): Signer {
    val crypto = CryptographyProvider.Default
    val rsa = crypto.get(RSA.PSS)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PSS(keyPair, "PS256")
}

/**
 * Generates an RSA-PSS key pair for PS384 signing (RSA-PSS with SHA-384).
 *
 * @return A Signer using PS384
 */
public suspend fun generatePS384Signer(): Signer {
    val crypto = CryptographyProvider.Default
    val rsa = crypto.get(RSA.PSS)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PSS(keyPair, "PS384")
}

/**
 * Generates an RSA-PSS key pair for PS512 signing (RSA-PSS with SHA-512).
 *
 * @return A Signer using PS512
 */
public suspend fun generatePS512Signer(): Signer {
    val crypto = CryptographyProvider.Default
    val rsa = crypto.get(RSA.PSS)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PSS(keyPair, "PS512")
}
