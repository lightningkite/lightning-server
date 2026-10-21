package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.services.data.ExperimentalLightningServer
import kotlinx.serialization.Serializable

/**
 * Lists the HMAC algorithms for generating time-based one-time passwords (TOTP), as defined in RFC 6238.
 *
 * [SHA1] is the only algorithm that every authenticator app supports, and HMAC-SHA1 remains secure for TOTP, so use it unless you control which apps your users have. The security of TOTP depends on the secret's entropy, not on the hash algorithm.
 */
@Serializable
public enum class TotpHashAlgorithm {
    /** Uses HMAC-SHA1, the default, which every major authenticator app supports. */
    SHA1,

    /**
     * Uses HMAC-SHA256, which has limited compatibility and is not supported by Authy or by Google Authenticator on some platforms.
     *
     * Use it only if you control which authenticator apps your users have.
     */
    @ExperimentalLightningServer("Authy does not support this, and therefore it is not recommended")
    SHA256,

    /**
     * Uses HMAC-SHA512, which has very limited compatibility and is not supported by Authy or many other authenticator apps.
     *
     * Use it only if you control which authenticator apps your users have, or implement the verification yourself.
     */
    @ExperimentalLightningServer("Authy does not support this, and therefore it is not recommended")
    SHA512,
}