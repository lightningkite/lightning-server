package com.lightningkite.lightningserver.guide.samples

// region encryption-imports
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant
// endregion encryption-imports
import com.lightningkite.lightningserver.runtime.test.*
import kotlin.time.Duration.Companion.minutes

// region encryption-keys
object VaultServer : ServerBuilder() {
    val noteCipher = secretBasis.cipher("notes-v1")
    val downloadSigner = secretBasis.signer("download-links-v1")
}
// endregion encryption-keys

// region encrypt
context(server: ServerRuntime)
suspend fun sealNote(text: String): ByteArray =
    VaultServer.noteCipher.await().encrypt(text.encodeToByteArray())

context(server: ServerRuntime)
suspend fun openNote(sealed: ByteArray): String =
    VaultServer.noteCipher.await().decrypt(sealed).decodeToString()
// endregion encrypt

// region sign
context(server: ServerRuntime)
suspend fun downloadSignature(fileId: String, validFor: Duration): Pair<Instant, String> {
    val expires = now() + validFor
    val signature = VaultServer.downloadSigner.await().sign("$fileId/${expires.epochSeconds}".encodeToByteArray())
    return expires to Base64.UrlSafe.encode(signature)
}

context(server: ServerRuntime)
suspend fun downloadAllowed(fileId: String, expires: Instant, signature: String): Boolean =
    expires > now() && VaultServer.downloadSigner.await().verify(
        "$fileId/${expires.epochSeconds}".encodeToByteArray(),
        Base64.UrlSafe.decode(signature),
    )
// endregion sign

// region password-hash
suspend fun passwordHashExample() {
    val stored = "correct horse battery".secureHash()
    check("correct horse battery".checkAgainstHash(stored))
    check(!"wrong".checkAgainstHash(stored))
}
// endregion password-hash

fun encryptionTest() = VaultServer.testBlocking(settings = {}) {
    execute {
        val sealed = sealNote("meet at noon")
        check(!sealed.decodeToString().contains("noon"))
        check(openNote(sealed) == "meet at noon")

        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        check(runCatching { openNote(tampered) }.isFailure)

        val (expires, signature) = downloadSignature("file-1", 5.minutes)
        check(downloadAllowed("file-1", expires, signature))
        check(!downloadAllowed("file-2", expires, signature))
        check(!downloadAllowed("file-1", expires + 1.minutes, signature))
    }
    passwordHashExample()
}
