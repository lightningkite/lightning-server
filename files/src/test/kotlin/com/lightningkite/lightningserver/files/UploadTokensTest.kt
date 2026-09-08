package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.HMAC_Blocking
import com.lightningkite.lightningserver.encryption.signBlocking
import com.lightningkite.lightningserver.encryption.signerBlocking
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The token layer carries no I/O, so its expiry and forgery behavior is testable directly rather than
 * through an endpoint.
 */
class UploadTokensTest {

    private val tokens = UploadTokens(SecretBasis().signerBlocking("upload-files"), Clock.System)
    private val other = UploadTokens(SecretBasis().signerBlocking("upload-files"), Clock.System)

    @Test
    fun scannedRoundTrips() {
        val signed = tokens.sign(UploadToken.Scanned("abc.file"), 1.minutes)
        assertEquals(UploadToken.Scanned("abc.file"), tokens.parseOrNull(signed))
    }

    @Test
    fun unscannedRoundTrips() {
        val signed = tokens.sign(UploadToken.Unscanned("abc.file"), 1.minutes)
        assertEquals(UploadToken.Unscanned("abc.file"), tokens.parseOrNull(signed))
    }

    /**
     * The promotion attack: take a legitimately issued unscanned token and rewrite its prefix so it
     * claims the file was scanned. The prefix sits inside the signed body, so this invalidates the
     * signature - but only attempting it proves that.
     */
    @Test
    fun rewritingThePrefixToClaimScannedIsRejected() {
        val unscanned = tokens.sign(UploadToken.Unscanned("abc.file"), 1.minutes)
        assertFailsWith<IllegalArgumentException> {
            tokens.parseOrNull(unscanned.replace("future:", "future-prescanned:"))
        }
    }

    /** Anything without one of our prefixes is not ours to judge - the caller handles it. */
    @Test
    fun foreignStringsAreNotClaimed() {
        assertNull(tokens.parseOrNull("https://example.com/file.txt"))
        assertNull(tokens.parseOrNull("data:text/plain;base64,VEVTVA=="))
        assertNull(tokens.parseOrNull("sf://files/uploaded/abc.file"))
        assertNull(tokens.parseOrNull(""))
    }

    @Test
    fun expiredTokenIsRejected() {
        val signed = tokens.sign(UploadToken.Scanned("abc.file"), (-1).minutes)
        assertFailsWith<IllegalArgumentException> { tokens.parseOrNull(signed) }
    }

    @Test
    fun signatureFromAnotherSecretIsRejected() {
        val signed = other.sign(UploadToken.Scanned("abc.file"), 1.minutes)
        assertFailsWith<IllegalArgumentException> { tokens.parseOrNull(signed) }
    }

    /** Repointing a valid token at another file must invalidate it - this is the whole point. */
    @Test
    fun tamperedKeyIsRejected() {
        val signed = tokens.sign(UploadToken.Scanned("mine.file"), 1.minutes)
        val tampered = signed.replace("mine.file", "your.file")
        assertFailsWith<IllegalArgumentException> { tokens.parseOrNull(tampered) }
    }

    /** Extending a token's life must invalidate it too; the expiry is inside the signed body. */
    @Test
    fun tamperedExpiryIsRejected() {
        val signed = tokens.sign(UploadToken.Scanned("abc.file"), 1.minutes)
        val useUntil = signed.substringAfter("useUntil=").substringBefore('&')
        val tampered = signed.replace(useUntil, (useUntil.toLong() + 60_000).toString())
        assertFailsWith<IllegalArgumentException> { tokens.parseOrNull(tampered) }
    }

    @Test
    fun missingSignatureIsRejected() {
        val signed = tokens.sign(UploadToken.Scanned("abc.file"), 1.minutes)
        assertFailsWith<IllegalArgumentException> { tokens.parseOrNull(signed.substringBefore("&token=")) }
    }

    /** A key carrying query syntax could smuggle its own useUntil, so it is refused at signing time. */
    @Test
    fun keyWithQuerySyntaxIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            tokens.sign(UploadToken.Scanned("abc.file?useUntil=99999999999999"), 1.minutes)
        }
    }

    /**
     * Wire compatibility: tokens are minted with a key derived from the secret basis under the variant
     * "upload-files", and live for a day. This endpoint previously signed with
     * `secretBasis.HMAC_Blocking("upload-files")` directly; `signerBlocking` must wrap that same key so
     * tokens already in clients' hands keep verifying across a deploy.
     */
    @Test
    fun signingKeyMatchesTheDirectHmacDerivation() {
        val basis = SecretBasis()
        val body = "future-prescanned:abc.file?useUntil=1234567890"
        val viaSigner = basis.signerBlocking("upload-files").signBlocking(body.encodeToByteArray())
        val viaRawKey = basis.HMAC_Blocking("upload-files")
            .signatureGenerator()
            .generateSignatureBlocking(body.encodeToByteArray())
        assertContentEquals(viaRawKey, viaSigner, "Deriving through Signer must not change the signing key")
    }

    /** Tokens go in a query parameter, so the signature must not contain '+' or '/'. */
    @Test
    fun signatureIsUrlSafe() {
        repeat(20) {
            val signed = tokens.sign(UploadToken.Scanned("file-$it.file"), 1.minutes)
            val signature = signed.substringAfter("&token=")
            assertFalse('+' in signature || '/' in signature, "Signature must be URL-safe base64: $signature")
        }
    }
}
