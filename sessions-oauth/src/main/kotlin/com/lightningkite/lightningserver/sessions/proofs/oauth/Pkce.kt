package com.lightningkite.lightningserver.sessions.proofs.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Provides helpers for PKCE (RFC 7636) and the CSRF `state` nonce of the OAuth authorization-code flow.
 *
 * All values come from a cryptographically secure random source and use the unpadded base64url alphabet, so they are safe in URLs.
 */
private val secureRandom = SecureRandom()

/** Returns [bytes] as unpadded base64url, per RFC 7636 Appendix A. */
private fun base64UrlNoPad(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

/** Returns a random 43-character token suitable for a `state` nonce or cache key. */
internal fun randomUrlToken(): String = base64UrlNoPad(ByteArray(32).also(secureRandom::nextBytes))

/** Returns a PKCE `code_verifier`, which is 43 random characters and so meets RFC 7636's 43-128 character requirement. */
internal fun generatePkceCodeVerifier(): String = randomUrlToken()

/** Returns the PKCE S256 `code_challenge` for [verifier], which is the unpadded base64url of its SHA-256. */
internal fun pkceCodeChallengeS256(verifier: String): String =
    base64UrlNoPad(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
