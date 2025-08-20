@file:Suppress("FunctionName")

package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.RSA

public suspend fun SecretBasis.HMAC(digest: CryptographyAlgorithmId<Digest>, variant: String): HMAC.Key = deriveKey(
    CryptographyProvider.Default.get(HMAC).keyDecoder(digest),
    HMAC.Key.Format.RAW,
    variant
)

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.ECDSA(
    curve: EC.Curve,
    variant: String,
    publicFormat: EC.PublicKey.Format = EC.PublicKey.Format.RAW,
    privateFormat: EC.PrivateKey.Format = EC.PrivateKey.Format.RAW
): ECDSA.KeyPair {
    val algorithm = CryptographyProvider.Default.get(ECDSA)
    val public = deriveKey(algorithm.publicKeyDecoder(curve), publicFormat, variant)
    val private = deriveKey(algorithm.privateKeyDecoder(curve), privateFormat, variant)

    return object : ECDSA.KeyPair {
        override val publicKey: ECDSA.PublicKey = public
        override val privateKey: ECDSA.PrivateKey = private
    }
}

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.RSA_PSS(
    digest: CryptographyAlgorithmId<Digest>,
    variant: String,
    publicFormat: RSA.PublicKey.Format = RSA.PublicKey.Format.DER,
    privateFormat: RSA.PrivateKey.Format = RSA.PrivateKey.Format.DER
): RSA.PSS.KeyPair {
    val algorithm = CryptographyProvider.Default.get(RSA.PSS)
    val public = deriveKey(algorithm.publicKeyDecoder(digest), publicFormat, variant)
    val private = deriveKey(algorithm.privateKeyDecoder(digest), privateFormat, variant)

    return object : RSA.PSS.KeyPair {
        override val publicKey: RSA.PSS.PublicKey = public
        override val privateKey: RSA.PSS.PrivateKey = private
    }
}

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.RSA_PKCS1(
    digest: CryptographyAlgorithmId<Digest>,
    variant: String,
    publicFormat: RSA.PublicKey.Format = RSA.PublicKey.Format.DER,
    privateFormat: RSA.PrivateKey.Format = RSA.PrivateKey.Format.DER
): RSA.PKCS1.KeyPair {
    val algorithm = CryptographyProvider.Default.get(RSA.PKCS1)
    val public = deriveKey(algorithm.publicKeyDecoder(digest), publicFormat, variant)
    val private = deriveKey(algorithm.privateKeyDecoder(digest), privateFormat, variant)

    return object : RSA.PKCS1.KeyPair {
        override val publicKey: RSA.PKCS1.PublicKey = public
        override val privateKey: RSA.PKCS1.PrivateKey = private
    }
}

