@file:Suppress("FunctionName")

package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.map
import com.lightningkite.lightningserver.definition.mapSuspending
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.SHA512

/**Uses ECDSA with P-521 curve and SHA-512 hashing*/
public suspend fun SecretBasis.hasher(variant: String): SecureHasher = ES512(variant)

/**Uses ECDSA with P-521 curve and SHA-512 hashing*/
public fun Runtime<SecretBasis>.hasher(variant: String): RuntimeDeferred<SecureHasher> = mapSuspending { it.hasher(variant) }


/**Uses ECDSA with P-521 curve and SHA-512 hashing*/
public fun SecretBasis.hasherBlocking(variant: String): SecureHasher = ECDSA_Blocking(variant, EC.Curve.P521).hasher(SHA512).withId("ES512")

/**Uses ECDSA with P-521 curve and SHA-512 hashing*/
public fun Runtime<SecretBasis>.hasherBlocking(variant: String): Runtime<SecureHasher> = map { it.hasherBlocking(variant) }


private fun SecureHasher.withId(id: String) = SecureHasher.WithId(this, id)

public suspend fun SecretBasis.HS256(variant: String): SecureHasher.WithId = HMAC(variant, SHA256).hasher().withId("HS256")
public suspend fun SecretBasis.HS384(variant: String): SecureHasher.WithId = HMAC(variant, SHA384).hasher().withId("HS384")
public suspend fun SecretBasis.HS512(variant: String): SecureHasher.WithId = HMAC(variant, SHA512).hasher().withId("HS512")

public suspend fun SecretBasis.RS256(variant: String): SecureHasher.WithId = RSA_PKCS1(variant, SHA256).hasher().withId("RS256")
public suspend fun SecretBasis.RS384(variant: String): SecureHasher.WithId = RSA_PKCS1(variant, SHA384).hasher().withId("RS384")
public suspend fun SecretBasis.RS512(variant: String): SecureHasher.WithId = RSA_PKCS1(variant, SHA512).hasher().withId("RS512")

public suspend fun SecretBasis.ES256(variant: String): SecureHasher.WithId = ECDSA(variant, EC.Curve.P256).hasher(SHA256).withId("ES256")
public suspend fun SecretBasis.ES384(variant: String): SecureHasher.WithId = ECDSA(variant, EC.Curve.P384).hasher(SHA384).withId("ES384")
public suspend fun SecretBasis.ES512(variant: String): SecureHasher.WithId = ECDSA(variant, EC.Curve.P521).hasher(SHA512).withId("ES512")


public suspend fun SecretBasis.HMAC(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
): HMAC.Key = deriveKey(
    CryptographyProvider.Default.get(HMAC).keyDecoder(digest),
    HMAC.Key.Format.RAW,
    variant
)

public fun SecretBasis.HMAC_Blocking(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
): HMAC.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(HMAC).keyDecoder(digest),
    HMAC.Key.Format.RAW,
    variant
)

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.ECDSA(
    variant: String,
    curve: EC.Curve = EC.Curve.P521,
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
public fun SecretBasis.ECDSA_Blocking(
    variant: String,
    curve: EC.Curve = EC.Curve.P521,
    publicFormat: EC.PublicKey.Format = EC.PublicKey.Format.RAW,
    privateFormat: EC.PrivateKey.Format = EC.PrivateKey.Format.RAW
): ECDSA.KeyPair {
    val algorithm = CryptographyProvider.Default.get(ECDSA)
    val public = deriveKeyBlocking(algorithm.publicKeyDecoder(curve), publicFormat, variant)
    val private = deriveKeyBlocking(algorithm.privateKeyDecoder(curve), privateFormat, variant)

    return object : ECDSA.KeyPair {
        override val publicKey: ECDSA.PublicKey = public
        override val privateKey: ECDSA.PrivateKey = private
    }
}

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.RSA_PSS(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
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
public fun SecretBasis.RSA_PSS_Blocking(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
    publicFormat: RSA.PublicKey.Format = RSA.PublicKey.Format.DER,
    privateFormat: RSA.PrivateKey.Format = RSA.PrivateKey.Format.DER
): RSA.PSS.KeyPair {
    val algorithm = CryptographyProvider.Default.get(RSA.PSS)
    val public = deriveKeyBlocking(algorithm.publicKeyDecoder(digest), publicFormat, variant)
    val private = deriveKeyBlocking(algorithm.privateKeyDecoder(digest), privateFormat, variant)

    return object : RSA.PSS.KeyPair {
        override val publicKey: RSA.PSS.PublicKey = public
        override val privateKey: RSA.PSS.PrivateKey = private
    }
}

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.RSA_PKCS1(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
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

@OptIn(CryptographyProviderApi::class)
public fun SecretBasis.RSA_PKCS1_Blocking(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
    publicFormat: RSA.PublicKey.Format = RSA.PublicKey.Format.DER,
    privateFormat: RSA.PrivateKey.Format = RSA.PrivateKey.Format.DER
): RSA.PKCS1.KeyPair {
    val algorithm = CryptographyProvider.Default.get(RSA.PKCS1)
    val public = deriveKeyBlocking(algorithm.publicKeyDecoder(digest), publicFormat, variant)
    val private = deriveKeyBlocking(algorithm.privateKeyDecoder(digest), privateFormat, variant)

    return object : RSA.PKCS1.KeyPair {
        override val publicKey: RSA.PKCS1.PublicKey = public
        override val privateKey: RSA.PKCS1.PrivateKey = private
    }
}
