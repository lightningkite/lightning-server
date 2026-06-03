package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.encryption.ES256
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.Signer
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA

/**
 * Derives a deterministic ES256 (ECDSA P-256) signer from this [SecretBasis], suitable for signing
 * OpenID Connect ID tokens.
 *
 * Unlike RSA (whose keys require prime generation and so cannot be reproduced from a seed), an EC
 * private key is simply a scalar, which is derived directly from the basis. This makes the OIDC
 * signing key **persistent by construction**: as long as the secret basis is stable — and it is a
 * managed server secret, the same one backing token encryption and HMAC signing — the same signing
 * key, and therefore the same published JWKS, is reproduced on every restart, with no separate key
 * storage to manage. This is the recommended way to configure [OpenIdProviderEndpoints.signingKey].
 *
 * @param variant Derivation label. Change it (e.g. append a version) to rotate the signing key.
 */
@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.oidcSigner(variant: String = "oidc-es256"): Signer {
    // P-256 private keys are 32-byte scalars; take the first 32 bytes of the HMAC-SHA512 derivation.
    val scalar = derive(variant).copyOf(32)
    val ecdsa = CryptographyProvider.Default.get(ECDSA)
    val priv = ecdsa.privateKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PrivateKey.Format.RAW, scalar)
    val pub = priv.getPublicKey()
    val keyPair = object : ECDSA.KeyPair {
        override val publicKey: ECDSA.PublicKey get() = pub
        override val privateKey: ECDSA.PrivateKey get() = priv
    }
    return keyPair.ES256()
}

/**
 * Convenience for wiring [OpenIdProviderEndpoints.signingKey] directly from the server's secret
 * basis setting. The result is cached, so the key is derived only once.
 *
 * ```kotlin
 * signingKey = secretBasis.oidcSigner()
 * ```
 */
public fun Runtime<SecretBasis>.oidcSigner(variant: String = "oidc-es256"): RuntimeDeferred<Signer> =
    RuntimeDeferred.Cached { this().oidcSigner(variant) }
