package com.lightningkite.lightningserver.auth.proof

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
    SHA256,

    /**
     * SHA512 HMAC with a hash of 64-bytes
     */
    SHA512,
}