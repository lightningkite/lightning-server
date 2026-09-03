package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signBlocking
import com.lightningkite.lightningserver.encryption.verifyBlocking
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A reference to a file uploaded through [UploadEarlyEndpoint].
 *
 * Which variant a token carries is the whole of the upload flow's safety: only [Scanned] names a file
 * that may be used, and only the verify endpoint mints one.
 */
public sealed interface UploadToken {
    /** Names the file within whichever location this variant refers to. */
    public val key: String

    /** Names a file in the jail: the client has somewhere to upload, but nothing has scanned it. */
    public data class Unscanned(override val key: String) : UploadToken

    /**
     * Names a file in the ready location, which passed scanning.
     *
     * The key is not the one the matching [Unscanned] carried - verify promotes under a fresh key so
     * that a certified file is never overwritten by a later upload to the same jail path.
     */
    public data class Scanned(override val key: String) : UploadToken

    public companion object {
        internal const val UNSCANNED_PREFIX: String = "future:"
        internal const val SCANNED_PREFIX: String = "future-prescanned:"
    }
}

/**
 * Mints and checks the tokens [UploadEarlyEndpoint] hands to clients.
 *
 * A token is `<prefix><key>?useUntil=<epoch millis>&token=<signature>`, signed with a key derived from
 * the server's secret basis. Clients treat it as opaque; the signature is what stops one from naming a
 * file it was not given, or from promoting its own upload to [UploadToken.Scanned].
 *
 * @param signer Signs and verifies tokens. Derive it from the secret basis rather than supplying a
 * bare key - see [UploadEarlyEndpoint].
 * @param clock Source of truth for expiration checks.
 */
public class UploadTokens(
    private val signer: Signer,
    private val clock: Clock,
) {
    /**
     * Produces the signed string form of [token], valid for [expiration] from now.
     */
    public fun sign(token: UploadToken, expiration: Duration): String {
        require('?' !in token.key && '&' !in token.key) {
            "An upload key cannot contain query parameters; got '${token.key}'."
        }
        val body = "${token.prefix}${token.key}?useUntil=${clock.now().plus(expiration).toEpochMilliseconds()}"
        return "$body&token=${Base64.UrlSafe.encode(signer.signBlocking(body.encodeToByteArray()))}"
    }

    /**
     * Recovers the token [signed] carries.
     *
     * @return null if [signed] is not one of our tokens at all, so the caller can go on to treat it as
     * something else
     * @throws IllegalArgumentException if it is one of ours but must not be honored - malformed,
     * expired, or carrying a signature we did not produce
     */
    public fun parseOrNull(signed: String): UploadToken? {
        val token = signed.substringBefore('?').let {
            when {
                it.startsWith(UploadToken.SCANNED_PREFIX) ->
                    UploadToken.Scanned(it.removePrefix(UploadToken.SCANNED_PREFIX))

                it.startsWith(UploadToken.UNSCANNED_PREFIX) ->
                    UploadToken.Unscanned(it.removePrefix(UploadToken.UNSCANNED_PREFIX))

                else -> return null
            }
        }

        val params = signed.substringAfter('?', "")
            .split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val useUntil = params["useUntil"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Upload token has no valid 'useUntil'.")
        val signature = params["token"]
            ?: throw IllegalArgumentException("Upload token has no signature.")

        // Expiry is checked before the signature only because it needs no crypto; a token failing
        // either check is refused just the same.
        if (Instant.fromEpochMilliseconds(useUntil) <= clock.now())
            throw IllegalArgumentException("Upload token has expired.")

        // Signed over the token without its signature, exactly as `sign` built it.
        val body = "${signed.substringBefore('?')}?useUntil=$useUntil"
        if (!signer.verifyBlocking(body.encodeToByteArray(), Base64.UrlSafe.decode(signature)))
            throw IllegalArgumentException("Upload token signature is not valid.")

        return token
    }

    private val UploadToken.prefix: String
        get() = when (this) {
            is UploadToken.Unscanned -> UploadToken.UNSCANNED_PREFIX
            is UploadToken.Scanned -> UploadToken.SCANNED_PREFIX
        }
}
