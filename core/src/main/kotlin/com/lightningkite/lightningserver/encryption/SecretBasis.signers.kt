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

/**Uses HMAC with SHA-512 hashing*/
public suspend fun SecretBasis.signer(variant: String): Signer.WithId = HS512(variant)

/**Uses HMAC with SHA-512 hashing*/
public fun Runtime<SecretBasis>.signer(variant: String): RuntimeDeferred<Signer.WithId> = RuntimeDeferred.Cached { this().signer(variant) }


/**Uses HMAC with SHA-512 hashing*/
public fun SecretBasis.signerBlocking(variant: String): Signer.WithId = HMAC_Blocking(variant, SHA512).signer().withId("ES512")

/**Uses HMAC with SHA-512 hashing*/
public fun Runtime<SecretBasis>.signerBlocking(variant: String): Runtime<Signer.WithId> = map { it.signerBlocking(variant) }


private fun Signer.withId(id: String) = Signer.WithId(this, id)

public suspend fun SecretBasis.HS256(variant: String): Signer.WithId = HMAC(variant, SHA256).signer().withId("HS256")
public suspend fun SecretBasis.HS384(variant: String): Signer.WithId = HMAC(variant, SHA384).signer().withId("HS384")
public suspend fun SecretBasis.HS512(variant: String): Signer.WithId = HMAC(variant, SHA512).signer().withId("HS512")


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
