package com.lightningkite.lightningserver.sessions.proofs.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Helpers implementing PKCE (Proof Key for Code Exchange, RFC 7636) and the CSRF `state` nonce
 * used to secure the OAuth authorization-code flow.
 *
 * All values are drawn from a cryptographically secure random source and encoded with the
 * URL-safe, unpadded BASE64URL alphabet (which is a subset of the RFC 3986 unreserved charset),
 * so they are safe to place directly in URLs.
 */
private val secureRandom = SecureRandom()

/** BASE64URL encoding without padding, per RFC 7636 Appendix A. */
private fun base64UrlNoPad(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

/** Generates an opaque, high-entropy token (43 chars) suitable for a `state` nonce or cache key. */
internal fun randomUrlToken(): String = base64UrlNoPad(ByteArray(32).also(secureRandom::nextBytes))

/**
 * Generates a PKCE `code_verifier`: a 43-character high-entropy string from the unreserved
 * charset, satisfying RFC 7636's 43-128 character requirement.
 */
internal fun generatePkceCodeVerifier(): String = randomUrlToken()

/** Computes the PKCE S256 `code_challenge` = BASE64URL-NOPAD(SHA256(verifier)). */
internal fun pkceCodeChallengeS256(verifier: String): String =
    base64UrlNoPad(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
