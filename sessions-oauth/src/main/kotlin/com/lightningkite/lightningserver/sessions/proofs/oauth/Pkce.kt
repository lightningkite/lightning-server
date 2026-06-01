package com.lightningkite.lightningserver.sessions.proofs.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Helpers for PKCE (RFC 7636).
 *
 * PKCE binds an authorization code to the same client that initiated the request, defending
 * against authorization-code interception even when no client secret can be used (mobile apps)
 * or when one is leaked. The verifier MUST be stored server-side and never sent through the IdP.
 */
public object Pkce {
    /**
     * A freshly generated PKCE pair.
     *
     * @property verifier The high-entropy secret kept server-side and sent with the token exchange.
     * @property challenge The SHA-256 hash of the verifier, sent in the authorization URL.
     */
    public data class Pair(public val verifier: String, public val challenge: String)

    /**
     * Generates a new verifier/challenge pair using S256.
     *
     * The verifier is 64 base64url characters of cryptographic randomness, comfortably within
     * the RFC 7636 range of 43–128 characters.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public fun generate(): Pair {
        val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = urlSafe.encode(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
        val challenge = urlSafe.encode(digest)
        return Pair(verifier, challenge)
    }
}
