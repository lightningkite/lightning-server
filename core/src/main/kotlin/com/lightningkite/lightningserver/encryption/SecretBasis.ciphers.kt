@file:Suppress("FunctionName")

package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.map
import com.lightningkite.lightningserver.definition.mapSuspending
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

/**Uses AES-GCM with 128-bit tag size*/
public suspend fun SecretBasis.cipher(variant: String): Cipher = AES_GCM(variant).cipher()

/**Uses AES-GCM with 128-bit tag size*/
public fun Runtime<SecretBasis>.cipher(variant: String): RuntimeDeferred<Cipher> = mapSuspending { it.cipher(variant) }

/**Uses AES-GCM with 128-bit tag size*/
public fun SecretBasis.cipherBlocking(variant: String): Cipher = AES_GCM_Blocking(variant).cipher()

/**Uses AES-GCM with 128-bit tag size*/
public fun Runtime<SecretBasis>.cipherBlocking(variant: String): Runtime<Cipher> = map { it.cipherBlocking(variant) }



public suspend fun SecretBasis.AES_CBC(
    variant: String,
    format: AES.Key.Format = AES.Key.Format.RAW
): AES.CBC.Key = deriveKey(
    CryptographyProvider.Default.get(AES.CBC).keyDecoder(),
    format,
    variant
)

public fun SecretBasis.AES_CBC_Blocking(
    variant: String,
    format: AES.Key.Format = AES.Key.Format.RAW
): AES.CBC.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.CBC).keyDecoder(),
    format,
    variant
)

public suspend fun SecretBasis.AES_CTR(
    variant: String,
    format: AES.Key.Format = AES.Key.Format.RAW
): AES.CTR.Key = deriveKey(
    CryptographyProvider.Default.get(AES.CTR).keyDecoder(),
    format,
    variant
)

public fun SecretBasis.AES_CTR_Blocking(
    variant: String,
    format: AES.Key.Format = AES.Key.Format.RAW
): AES.CTR.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.CTR).keyDecoder(),
    format,
    variant
)

public suspend fun SecretBasis.AES_GCM(
    variant: String,
    format: AES.Key.Format = AES.Key.Format.RAW
): AES.GCM.Key = deriveKey(
    CryptographyProvider.Default.get(AES.GCM).keyDecoder(),
    format,
    variant
)

public fun SecretBasis.AES_GCM_Blocking(
    variant: String,
    format: AES.Key.Format = AES.Key.Format.RAW
): AES.GCM.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.GCM).keyDecoder(),
    format,
    variant
)

@OptIn(CryptographyProviderApi::class)
public suspend fun SecretBasis.RSA_OAEP(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
    publicFormat: RSA.PublicKey.Format = RSA.PublicKey.Format.DER,
    privateFormat: RSA.PrivateKey.Format = RSA.PrivateKey.Format.DER
): RSA.OAEP.KeyPair {
    val algorithm = CryptographyProvider.Default.get(RSA.OAEP)
    val public = deriveKey(algorithm.publicKeyDecoder(digest), publicFormat, variant)
    val private = deriveKey(algorithm.privateKeyDecoder(digest), privateFormat, variant)

    return object : RSA.OAEP.KeyPair {
        override val publicKey: RSA.OAEP.PublicKey = public
        override val privateKey: RSA.OAEP.PrivateKey = private
    }
}

@OptIn(CryptographyProviderApi::class)
public fun SecretBasis.RSA_OAEP_Blocking(
    variant: String,
    digest: CryptographyAlgorithmId<Digest> = SHA512,
    publicFormat: RSA.PublicKey.Format = RSA.PublicKey.Format.DER,
    privateFormat: RSA.PrivateKey.Format = RSA.PrivateKey.Format.DER
): RSA.OAEP.KeyPair {
    val algorithm = CryptographyProvider.Default.get(RSA.OAEP)
    val public = deriveKeyBlocking(algorithm.publicKeyDecoder(digest), publicFormat, variant)
    val private = deriveKeyBlocking(algorithm.privateKeyDecoder(digest), privateFormat, variant)

    return object : RSA.OAEP.KeyPair {
        override val publicKey: RSA.OAEP.PublicKey = public
        override val privateKey: RSA.OAEP.PrivateKey = private
    }
}

public fun RSA.OAEP.KeyPair.cipher(): Cipher = Cipher(publicKey.encryptor(), privateKey.decryptor())

@OptIn(CryptographyProviderApi::class)
private data class CipherFromParts(
    val encryptor: Encryptor,
    val decryptor: Decryptor
): Cipher, Encryptor by encryptor, Decryptor by decryptor

public fun Cipher(encryptor: Encryptor, decryptor: Decryptor): Cipher = CipherFromParts(encryptor, decryptor)