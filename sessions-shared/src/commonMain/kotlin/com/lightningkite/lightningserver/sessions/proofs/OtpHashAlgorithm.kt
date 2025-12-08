package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.services.data.ExperimentalLightningServer
import kotlinx.serialization.Serializable

/**
 * HMAC algorithms supported for Time-based One-Time Password (TOTP) generation, as defined in RFC 6238.
 *
 * TOTP is used for two-factor authentication with authenticator apps like Google Authenticator, Authy,
 * Microsoft Authenticator, etc.
 *
 * ## Compatibility Note
 * While RFC 6238 defines multiple algorithms, **SHA1 is the only algorithm with universal support** across
 * all authenticator apps. SHA256 and SHA512 are technically more secure but have limited compatibility.
 *
 * ## Security Considerations
 * - **SHA1**: While SHA1 has known collision vulnerabilities in other contexts (file integrity, certificates),
 *   it remains secure for TOTP/HMAC use cases. The HMAC construction protects against known SHA1 weaknesses.
 *   NIST and security experts still consider HMAC-SHA1 acceptable for authentication tokens.
 *
 * - **SHA256/SHA512**: Offer larger hash outputs and stronger theoretical security guarantees, but the
 *   practical security improvement for TOTP is minimal. The primary security of TOTP comes from the secret
 *   key's entropy and proper rotation, not the hash algorithm choice.
 *
 * ## Recommendation
 * Use [SHA1] for maximum compatibility unless you have specific requirements for stronger algorithms AND
 * can control the authenticator apps your users will use.
 */
@Serializable
public enum class TotpHashAlgorithm {
    /**
     * SHA1 HMAC with a 20-byte (160-bit) hash output.
     *
     * This is the **recommended and default algorithm** for TOTP as it provides:
     * - Universal compatibility with all major authenticator apps (Google Authenticator, Authy, Microsoft Authenticator, etc.)
     * - RFC 6238 compliance (the TOTP standard)
     * - Sufficient security for time-based authentication codes
     *
     * Despite SHA1's known collision vulnerabilities in other contexts, HMAC-SHA1 remains cryptographically
     * secure for authentication purposes. The HMAC construction prevents exploitation of SHA1's weaknesses,
     * and TOTP's time-limited nature (typically 30-second windows) provides additional security.
     */
    SHA1,

    /**
     * SHA256 HMAC with a 32-byte (256-bit) hash output.
     *
     * While technically supported by RFC 6238, this algorithm has **limited compatibility**:
     * - **Not supported by**: Authy, Google Authenticator (on some platforms)
     * - **Supported by**: Some newer authenticator apps, Microsoft Authenticator
     *
     * Only use this if you can control which authenticator apps your users will use, or if you have
     * specific security requirements that mandate stronger algorithms.
     *
     * **Note**: The security improvement over SHA1 for TOTP use cases is minimal, as TOTP security
     * primarily depends on secret key entropy and proper key management.
     */
    @ExperimentalLightningServer("Authy does not support this, and therefore it is not recommended")
    SHA256,

    /**
     * SHA512 HMAC with a 64-byte (512-bit) hash output.
     *
     * While technically supported by RFC 6238, this algorithm has **very limited compatibility**:
     * - **Not supported by**: Authy, Google Authenticator (on some platforms), many authenticator apps
     * - **Supported by**: Very few authenticator implementations
     *
     * Only use this if you have a controlled environment where you can guarantee compatible authenticator
     * apps, or if you're implementing custom TOTP verification (not using standard authenticator apps).
     *
     * **Note**: The security improvement over SHA1 or SHA256 for TOTP use cases is negligible, as TOTP
     * security primarily depends on secret key entropy, proper key management, and time synchronization.
     */
    @ExperimentalLightningServer("Authy does not support this, and therefore it is not recommended")
    SHA512,
}