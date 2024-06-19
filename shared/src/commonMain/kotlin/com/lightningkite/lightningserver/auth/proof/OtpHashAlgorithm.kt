package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.ExperimentalLightningServer
import kotlinx.serialization.Serializable

@Serializable
enum class OtpHashAlgorithm {
    /**
     * SHA1 HMAC with a hash of 20-bytes
     */
    SHA1,

    /**
     * SHA256 HMAC with a hash of 32-bytes
     */
    @ExperimentalLightningServer("Authy does not support this, and therefore it is not recommended")
    SHA256,

    /**
     * SHA512 HMAC with a hash of 64-bytes
     */
    @ExperimentalLightningServer("Authy does not support this, and therefore it is not recommended")
    SHA512,
}