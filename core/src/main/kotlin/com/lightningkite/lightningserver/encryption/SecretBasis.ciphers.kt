@file:Suppress("FunctionName")

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


/**Uses AES-GCM with 128-bit tag size*/
public suspend fun SecretBasis.cipher(variant: String): Cipher = AES_GCM(variant).cipher()

/**Uses AES-GCM with 128-bit tag size*/
public fun Runtime<SecretBasis>.cipher(variant: String): RuntimeDeferred<Cipher> = RuntimeDeferred.Cached { this().cipher(variant) }

/**Uses AES-GCM with 128-bit tag size*/
public fun SecretBasis.cipherBlocking(variant: String): Cipher = AES_GCM_Blocking(variant).cipher()

/**Uses AES-GCM with 128-bit tag size*/
public fun Runtime<SecretBasis>.cipherBlocking(variant: String): Runtime<Cipher> = Runtime.Cached { this().cipherBlocking(variant) }


@Suppress("ClassName")
public enum class AES_KeySize(public val size: BinarySize) {
    B128(AES.Key.Size.B128),
    B192(AES.Key.Size.B192),
    B256(AES.Key.Size.B256)
}

public suspend fun SecretBasis.AES_CBC(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CBC.Key = deriveKey(
    CryptographyProvider.Default.get(AES.CBC).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

public fun SecretBasis.AES_CBC_Blocking(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CBC.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.CBC).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

public suspend fun SecretBasis.AES_CTR(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CTR.Key = deriveKey(
    CryptographyProvider.Default.get(AES.CTR).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

public fun SecretBasis.AES_CTR_Blocking(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.CTR.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.CTR).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

public suspend fun SecretBasis.AES_GCM(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.GCM.Key = deriveKey(
    CryptographyProvider.Default.get(AES.GCM).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

public fun SecretBasis.AES_GCM_Blocking(
    variant: String,
    size: AES_KeySize = AES_KeySize.B256
): AES.GCM.Key = deriveKeyBlocking(
    CryptographyProvider.Default.get(AES.GCM).keyDecoder(),
    AES.Key.Format.RAW,
    variant,
    size.size
)

@OptIn(CryptographyProviderApi::class)
private data class CipherFromParts(
    val encryptor: Encryptor,
    val decryptor: Decryptor
): Cipher, Encryptor by encryptor, Decryptor by decryptor

public fun Cipher(encryptor: Encryptor, decryptor: Decryptor): Cipher = CipherFromParts(encryptor, decryptor)