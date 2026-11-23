# Encryption

`com.lightningkite.lightningserver.encryption` (in `core`) derives every key the server needs from one master secret,
the `secretBasis` setting.  Ask it for a cipher or signer by purpose name and you get an independent key for that
purpose.  Sessions, proofs, and tokens use it already; use it yourself to encrypt fields, sign links, or hash
passwords.  It is built on [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin).

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/EncryptionSamples.kt#encryption-imports -->
```kotlin
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Instant
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/EncryptionSamples.kt#encryption-keys -->
```kotlin
object VaultServer : ServerBuilder() {
    val noteCipher = secretBasis.cipher("notes-v1")
    val downloadSigner = secretBasis.signer("download-links-v1")
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/EncryptionSamples.kt#encrypt -->
```kotlin
context(server: ServerRuntime)
suspend fun sealNote(text: String): ByteArray =
    VaultServer.noteCipher.await().encrypt(text.encodeToByteArray())

context(server: ServerRuntime)
suspend fun openNote(sealed: ByteArray): String =
    VaultServer.noteCipher.await().decrypt(sealed).decodeToString()
```

`cipher` is AES-256-GCM: the output includes a random nonce and an authentication tag, so encrypting the same text
twice gives different bytes, and `decrypt` throws if the data was altered or encrypted with another key.  The key is
derived once and cached.

## Signing

A signer (HMAC-SHA512) proves a value came from your server without hiding it.  Sign everything the receiver must
trust, including the expiry:

<!-- sample: com/lightningkite/lightningserver/guide/samples/EncryptionSamples.kt#sign -->
```kotlin
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
```

`Signer.sign(string)` and `Signer.verify(string, signature)` do the same with standard Base64, whose `+` and `/` need
escaping in a URL; that's why the example above uses the `ByteArray` overloads with URL-safe Base64.

## Password hashing

<!-- sample: com/lightningkite/lightningserver/guide/samples/EncryptionSamples.kt#password-hash -->
```kotlin
suspend fun passwordHashExample() {
    val stored = "correct horse battery".secureHash()
    check("correct horse battery".checkAgainstHash(stored))
    check(!"wrong".checkAgainstHash(stored))
}
```

`secureHash` is PBKDF2-HMAC-SHA512 with a random salt and 100,000 iterations, deliberately slow (around 100 ms), and
produces `PBKDF2WithHmacSHA512.<salt>.<hash>`.  Rate-limit anything that calls it on user input.  For secrets that are
already random and long (API keys, device tokens), `fastHash` (salted SHA-256) is enough; `checkAgainstHash`
verifies both formats.  For user passwords, prefer `PasswordProofEndpoints` (see
[Proof-based login](proof-session.md#password)), which does this for you.

## The `secretBasis` setting

`secretBasis` is registered on every server; you don't declare it.  It is a random 512-bit value written to the
settings file the first time the server generates one:

```json
{
    "secretBasis": "kX2r...base64...=="
}
```

- Every instance of a deployment must share it.  If it is missing, each process makes up its own, and tokens and
  proofs from one instance fail on another.  The AWS Terraform emitters create one with `secretBasis.generated()`.
- Changing it logs everyone out and makes everything encrypted or signed with it unreadable.
- To isolate a module's keys, declare another one: `val vaultBasis = setting("vaultBasis", SecretBasis())`, then
  `vaultBasis.cipher("notes-v1")`.

Variant names are what keep keys apart, so give each purpose its own, and put a version in it.  To rotate a key, read
with the old variant and write with the new one until old data is gone.

## Other algorithms

| Function on `SecretBasis` | Returns |
|---|---|
| `cipher(variant)`, `cipherBlocking(variant)` | AES-256-GCM `Cipher` |
| `AES_GCM`, `AES_CBC`, `AES_CTR(variant, size)` (and `_Blocking`) | The raw AES key; `size` is `AES_KeySize.B128`, `B192`, or `B256` (default) |
| `signer(variant)`, `signerBlocking(variant)` | HS512 `Signer` |
| `HS256`, `HS384`, `HS512(variant)` | HMAC `Signer` with that digest |
| `derive(variant, size)`, `deriveKey(decoder, format, variant)` | Raw derived bytes, or a key for any cryptography-kotlin algorithm |

`cipher` and `signer` on the `secretBasis` setting itself (as in the example) return a `RuntimeDeferred`; call
`await()` inside a server context.  On a `SecretBasis` value they are plain suspend functions.  CBC and CTR don't
authenticate the data: use GCM unless an outside system requires otherwise.

For keys a third party must verify without being able to sign, `Signer.ECDSA` (`keyPair.ES256()`, `ES384()`,
`ES512()`), `Signer.RSA_PSS`, and `Signer.RSA_PKCS1` wrap key pairs you generate or load with cryptography-kotlin.

## See also

- [Proof-based login](proof-session.md#token-formats): token formats built on these keys
- [Settings](settings.md)
- [Terraform](terraform.md)
