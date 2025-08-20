@file:Suppress("FunctionName", "ClassName")

package com.lightningkite.lightningserver.encryption

import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.SHA512

public interface SecureHasher {
    public suspend fun sign(bytes: ByteArray): ByteArray
    public suspend fun verify(bytes: ByteArray, signature: ByteArray): Boolean

    public class HMAC(public val key: HMAC.Key) : SecureHasher {
        override suspend fun sign(bytes: ByteArray): ByteArray =
            key.signatureGenerator().generateSignature(bytes)

        override suspend fun verify(bytes: ByteArray, signature: ByteArray): Boolean =
            key.signatureVerifier().tryVerifySignature(bytes, signature)
    }

    public class CMAC(public val key: AES.CMAC.Key) : SecureHasher {
        override suspend fun sign(bytes: ByteArray): ByteArray =
            key.signatureGenerator().generateSignature(bytes)

        override suspend fun verify(bytes: ByteArray, signature: ByteArray): Boolean =
            key.signatureVerifier().tryVerifySignature(bytes, signature)
    }

    public class ECDSA(
        public val keyPair: ECDSA.KeyPair,
        public val digest: CryptographyAlgorithmId<Digest>,
        public val format: ECDSA.SignatureFormat
    ) : SecureHasher {
        override suspend fun sign(bytes: ByteArray): ByteArray =
            keyPair.privateKey.signatureGenerator(digest, format).generateSignature(bytes)

        override suspend fun verify(bytes: ByteArray, signature: ByteArray): Boolean =
            keyPair.publicKey.signatureVerifier(digest, format).tryVerifySignature(bytes, signature)
    }

    public class RSA_PSS(public val keyPair: RSA.PSS.KeyPair) : SecureHasher {
        override suspend fun sign(bytes: ByteArray): ByteArray =
            keyPair.privateKey.signatureGenerator().generateSignature(bytes)

        override suspend fun verify(bytes: ByteArray, signature: ByteArray): Boolean =
            keyPair.publicKey.signatureVerifier().tryVerifySignature(bytes, signature)
    }

    public class RSA_PKCS1(public val keyPair: RSA.PKCS1.KeyPair) : SecureHasher {
        override suspend fun sign(bytes: ByteArray): ByteArray =
            keyPair.privateKey.signatureGenerator().generateSignature(bytes)

        override suspend fun verify(bytes: ByteArray, signature: ByteArray): Boolean =
            keyPair.publicKey.signatureVerifier().tryVerifySignature(bytes, signature)
    }

    public data class WithId(public val hasher: SecureHasher, public val id: String) : SecureHasher by hasher
}

public fun HMAC.Key.hasher(): SecureHasher = SecureHasher.HMAC(this)
public fun AES.CMAC.Key.hasher(): SecureHasher = SecureHasher.CMAC(this)

public fun ECDSA.KeyPair.hasher(
    digest: CryptographyAlgorithmId<Digest> = SHA512,
    format: ECDSA.SignatureFormat = ECDSA.SignatureFormat.RAW
): SecureHasher = SecureHasher.ECDSA(this, digest, format)

public fun RSA.PSS.KeyPair.hasher(): SecureHasher = SecureHasher.RSA_PSS(this)
public fun RSA.PKCS1.KeyPair.hasher(): SecureHasher = SecureHasher.RSA_PKCS1(this)


private fun SecureHasher.withId(id: String) = SecureHasher.WithId(this, id)

public suspend fun SecretBasis.HS256(variant: String): SecureHasher.WithId = HMAC(SHA256, variant).hasher().withId("HS256")
public suspend fun SecretBasis.HS384(variant: String): SecureHasher.WithId = HMAC(SHA384, variant).hasher().withId("HS384")
public suspend fun SecretBasis.HS512(variant: String): SecureHasher.WithId = HMAC(SHA512, variant).hasher().withId("HS512")

public suspend fun SecretBasis.RS256(variant: String): SecureHasher.WithId = RSA_PKCS1(SHA256, variant).hasher().withId("RS256")
public suspend fun SecretBasis.RS384(variant: String): SecureHasher.WithId = RSA_PKCS1(SHA384, variant).hasher().withId("RS384")
public suspend fun SecretBasis.RS512(variant: String): SecureHasher.WithId = RSA_PKCS1(SHA512, variant).hasher().withId("RS512")

public suspend fun SecretBasis.ES256(variant: String): SecureHasher.WithId = ECDSA(EC.Curve.P256, variant).hasher(SHA256).withId("ES256")
public suspend fun SecretBasis.ES384(variant: String): SecureHasher.WithId = ECDSA(EC.Curve.P384, variant).hasher(SHA384).withId("ES384")
public suspend fun SecretBasis.ES512(variant: String): SecureHasher.WithId = ECDSA(EC.Curve.P521, variant).hasher(SHA512).withId("ES512")

