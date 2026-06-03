@file:Suppress("FunctionName")

package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.encryption.Signer
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA

/**
 * Generates an RSA-PKCS1 key pair for RS256 signing (RSA with SHA-256).
 *
 * RS256 is the required minimum signing algorithm for OpenID Connect ID tokens. The private key
 * signs tokens (kept secret); the public key verifies them (published via the JWKS endpoint).
 *
 * **Production note:** a fresh key is generated on each call, so do NOT call this at startup in
 * production — every restart would invalidate all previously issued ID tokens. Persist the key
 * (e.g. via a secret source) and load it instead.
 */
public suspend fun generateRS256Signer(): Signer {
    val rsa = CryptographyProvider.Default.get(RSA.PKCS1)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PKCS1(keyPair, "RS256")
}

/** Generates an RSA-PKCS1 key pair for RS384 signing. See [generateRS256Signer] for caveats. */
public suspend fun generateRS384Signer(): Signer {
    val rsa = CryptographyProvider.Default.get(RSA.PKCS1)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PKCS1(keyPair, "RS384")
}

/** Generates an RSA-PKCS1 key pair for RS512 signing. See [generateRS256Signer] for caveats. */
public suspend fun generateRS512Signer(): Signer {
    val rsa = CryptographyProvider.Default.get(RSA.PKCS1)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PKCS1(keyPair, "RS512")
}

/** Generates an RSA-PSS key pair for PS256 signing. More modern than PKCS1, but less widely supported. */
public suspend fun generatePS256Signer(): Signer {
    val rsa = CryptographyProvider.Default.get(RSA.PSS)
    val keyPair = rsa.keyPairGenerator().generateKey()
    return Signer.RSA_PSS(keyPair, "PS256")
}
