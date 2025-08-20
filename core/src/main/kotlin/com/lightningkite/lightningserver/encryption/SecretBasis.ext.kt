package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.mapSuspending

/**Uses ECDSA with P-521 curve and SHA-512 hashing*/
public suspend fun SecretBasis.hasher(variant: String): SecureHasher = ES512(variant)

/**Uses ECDSA with P-521 curve and SHA-512 hashing*/
public fun Runtime<SecretBasis>.hasher(variant: String): RuntimeDeferred<SecureHasher> = mapSuspending { it.hasher(variant) }

