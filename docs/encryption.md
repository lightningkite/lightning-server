# Encryption and Cryptography

Lightning Server provides a comprehensive cryptography package for secure key management, encryption, signing, and password hashing. The package is built on the `dev.whyoleg.cryptography` library and provides a simplified API tailored for server applications.

## Overview

The encryption package (`com.lightningkite.lightningserver.encryption`) provides:

- **SecretBasis**: Master secret for deriving application-specific keys
- **Ciphers**: AES encryption in various modes (GCM, CBC, CTR)
- **Signers**: HMAC and asymmetric signature generation/verification
- **Secure Hashing**: PBKDF2-based password hashing

## SecretBasis: Master Key Management

`SecretBasis` is a 512-bit master secret that serves as the foundation for all cryptographic operations. It uses HMAC-SHA512 for key derivation, ensuring that each variant produces a cryptographically independent key.

### Creating a SecretBasis

Lightning Server provides a **global `secretBasis` setting** that is automatically available in all server applications:

```kotlin
import com.lightningkite.lightningserver.definition.secretBasis

// Use the global secret basis (recommended)
val cipher = secretBasis().cipher("user-tokens")
val signer = secretBasis().signer("session-auth")
```

The global `secretBasis` is automatically generated on first run and stored in your `settings.json` file. **This is the recommended approach** as it ensures a single master secret is used consistently across your application.

Alternatively, you can create your own SecretBasis settings for specific purposes:

```kotlin
object Server : ServerBuilder() {
    // Custom secret basis for specific module
    val moduleSecret = setting("moduleSecret", SecretBasis())

    // Derive different keys for different purposes
    val tokenCipher = moduleSecret.cipher("user-tokens")
    val sessionSigner = moduleSecret.signer("session-auth")
}
```

### Best Practices

1. **Use the global `secretBasis`**: Unless you have specific module isolation requirements, use the built-in global setting
2. **Generate once, store securely**: The secret is auto-generated on first run and stored in `settings.json`
3. **Use variants for different purposes**: Derive different keys for different use cases (tokens, sessions, cookies, etc.)
4. **Never hardcode secrets**: Always load from configuration
5. **Never commit `settings.json`**: Add it to `.gitignore` to prevent exposing secrets

**⚠️ Warning**: Changing the secret basis will invalidate all existing encrypted data and active user sessions. Only change this value during initial setup or in controlled migration scenarios.

## Encryption with Ciphers

Lightning Server supports AES encryption in multiple modes. **AES-GCM is recommended** as it provides both confidentiality and authentication (AEAD - Authenticated Encryption with Associated Data).

### Quick Start

```kotlin
suspend fun example(basis: SecretBasis) {
    // Get a cipher for a specific purpose
    val cipher = basis.cipher("user-data")
    
    // Encrypt data
    val plaintext = "sensitive information".encodeToByteArray()
    val ciphertext = cipher.encrypt(plaintext)
    
    // Decrypt data
    val decrypted = cipher.decrypt(ciphertext)
    println(String(decrypted)) // "sensitive information"
}
```

### Available Cipher Modes

```kotlin
// AES-GCM (RECOMMENDED - provides authentication)
val gcmKey = basis.AES_GCM("variant", AES_KeySize.B256)
val cipher = gcmKey.cipher()

// AES-CBC (Confidentiality only, no authentication)
val cbcKey = basis.AES_CBC("variant", AES_KeySize.B256)

// AES-CTR (Confidentiality only, no authentication)
val ctrKey = basis.AES_CTR("variant", AES_KeySize.B256)
```

### Key Sizes

AES supports three key sizes:
- `AES_KeySize.B128` - 128-bit keys (sufficient for most uses)
- `AES_KeySize.B192` - 192-bit keys
- `AES_KeySize.B256` - 256-bit keys (recommended, default)

### Blocking vs Suspending

All cryptographic operations have both suspending and blocking versions:

```kotlin
// Suspending (use in coroutines)
suspend fun useSuspending(basis: SecretBasis) {
    val cipher = basis.cipher("variant")
    val encrypted = cipher.encrypt(data)
}

// Blocking (use in non-coroutine contexts)
fun useBlocking(basis: SecretBasis) {
    val cipher = basis.cipherBlocking("variant")
    val encrypted = cipher.encrypt(data)
}
```

## Digital Signatures

Signers provide cryptographic signature generation and verification, commonly used for JWTs, API authentication, and data integrity verification.

### HMAC Signatures (Symmetric)

HMAC uses the same key for both signing and verification. This is suitable when both parties share a secret.

```kotlin
suspend fun signData(basis: SecretBasis) {
    // Get a signer (defaults to HS512)
    val signer = basis.signer("api-tokens")
    
    // Sign data
    val data = "message to sign".encodeToByteArray()
    val signature = signer.sign(data)
    
    // Verify signature
    val isValid = signer.verify(data, signature)
    println(isValid) // true
}
```

### JWT-Compatible Signers

The library provides JWT algorithm-compatible signers:

```kotlin
// HS256 (HMAC with SHA-256)
val hs256 = basis.HS256("variant")

// HS384 (HMAC with SHA-384)
val hs384 = basis.HS384("variant")

// HS512 (HMAC with SHA-512) - RECOMMENDED
val hs512 = basis.HS512("variant")
```

### String Signing (Convenience)

For text data, use the string signing functions which handle Base64 encoding:

```kotlin
suspend fun signString(signer: Signer) {
    val message = "Hello, world!"
    val signature = signer.sign(message) // Returns Base64 string
    val isValid = signer.verify(message, signature)
}
```

### Asymmetric Signatures (ECDSA, RSA)

For scenarios requiring public key verification (e.g., JWTs verified by third parties):

```kotlin
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ECDSA

suspend fun useECDSA() {
    // Generate ECDSA key pair
    val provider = CryptographyProvider.Default
    val ecdsa = provider.get(ECDSA.SHA256)
    val keyPair = ecdsa.keyGenerator(ECDSA.Curve.P256).generateKey()
    
    // Create signer
    val signer = keyPair.ES256()
    
    // Sign with private key
    val signature = signer.sign("data".encodeToByteArray())
    
    // Verify with public key (can be shared publicly)
    val isValid = signer.verify("data".encodeToByteArray(), signature)
}
```

## Password Hashing

For password storage, use the secure hashing functions that implement PBKDF2-HMAC-SHA512 with 100,000 iterations.

### Hashing Passwords

```kotlin
suspend fun registerUser(password: String) {
    // Hash the password (takes ~100-200ms)
    val hash = password.secureHash()
    
    // Store hash in database
    database.users.insertOne(User(
        email = "user@example.com",
        passwordHash = hash
    ))
}
```

### Verifying Passwords

```kotlin
suspend fun loginUser(email: String, password: String): Boolean {
    // Fetch user from database
    val user = database.users.findOne { it.email eq email } ?: return false
    
    // Verify password (takes ~100-200ms)
    return password.checkAgainstHash(user.passwordHash)
}
```

### Important Notes

1. **Slow by design**: These functions take 100-200ms intentionally to resist brute-force attacks
2. **Call once per request**: Only hash/verify once during registration or authentication
3. **Automatic salting**: Each hash uses a unique random salt, so the same password produces different hashes
4. **Rate limiting**: Protect login endpoints with rate limiting to prevent DoS attacks
5. **Format**: Hashes have the format `PBKDF2WithHmacSHA512.<salt>.<hash>` (both Base64-encoded)

## Complete Example: Encrypted Session Tokens

```kotlin
object Server : ServerBuilder() {
    val secret = setting("secret", SecretBasis())
    
    data class SessionData(
        val userId: String,
        val expiresAt: Instant
    )
    
    val sessionCipher = Runtime.Cached { secret().cipherBlocking("sessions") }
    
    fun createSessionToken(userId: String): String {
        val session = SessionData(
            userId = userId,
            expiresAt = Clock.System.now() + 24.hours
        )
        val json = Json.encodeToString(session)
        val encrypted = sessionCipher().encrypt(json.encodeToByteArray())
        return Base64.encode(encrypted)
    }
    
    fun validateSessionToken(token: String): SessionData? {
        return try {
            val encrypted = Base64.decode(token)
            val decrypted = sessionCipher().decrypt(encrypted)
            val session = Json.decodeFromString<SessionData>(
                String(decrypted)
            )
            
            if (session.expiresAt < Clock.System.now()) {
                null // Expired
            } else {
                session
            }
        } catch (e: Exception) {
            null // Invalid token
        }
    }
}
```

## Security Best Practices

### Key Management

1. **Never commit secrets to version control**: Use `.gitignore` for `settings.json`
2. **Rotate keys periodically**: Plan for key rotation by versioning your variants
3. **Use environment-specific secrets**: Different secrets for dev/staging/production

### Encryption

1. **Always use AES-GCM**: Unless you have specific compatibility requirements
2. **Don't reuse variants**: Use unique variant names for different purposes
3. **Handle decryption failures gracefully**: Assume tampered data and reject silently

### Passwords

1. **Never log passwords**: Not even hashed ones
2. **Use rate limiting**: Protect against brute-force attacks
3. **Consider MFA**: Two-factor authentication for sensitive operations
4. **Enforce password policies**: Minimum length, complexity requirements

### General

1. **Keep libraries updated**: Security patches are critical
2. **Use HTTPS everywhere**: Encryption doesn't help if transport is insecure
3. **Validate all inputs**: Don't trust data just because it's encrypted/signed
4. **Monitor for security issues**: Log authentication failures and unusual patterns

## Performance Considerations

### Cipher Operations

- **Encryption/Decryption**: Very fast (~microseconds for small data)
- **Key Derivation**: Fast, cached after first use
- **Recommendation**: Derive keys once and cache them

### Signing Operations

- **HMAC Signing**: Very fast (~microseconds)
- **ECDSA Signing**: Slower but still fast (~milliseconds)
- **Recommendation**: Fine for request-level operations

### Password Hashing

- **Hashing/Verification**: Slow by design (~100-200ms)
- **Recommendation**: Only call once per authentication attempt, use session tokens afterward

## Common Patterns

### Variant Naming Convention

Use descriptive, hierarchical names for variants:

```kotlin
// Good
basis.cipher("user-tokens-v1")
basis.signer("api-hmac-v2")
basis.cipher("session-cookies-production")

// Avoid
basis.cipher("cipher1")
basis.signer("s")
```

### Key Rotation

Plan for key rotation by including version numbers:

```kotlin
val currentVersion = 2
val cipher = when (currentVersion) {
    1 -> basis.cipher("tokens-v1")
    2 -> basis.cipher("tokens-v2")
    else -> error("Unknown version")
}

// During rotation, support both:
fun decrypt(data: ByteArray, version: Int): ByteArray {
    return when (version) {
        1 -> basis.cipherBlocking("tokens-v1").decrypt(data)
        2 -> basis.cipherBlocking("tokens-v2").decrypt(data)
        else -> error("Unknown version")
    }
}
```

### Caching Derived Keys

Use `Runtime.Cached` for frequently-used keys:

```kotlin
object Server : ServerBuilder() {
    val secret = setting("secret", SecretBasis())
    
    // Cache the cipher to avoid repeated derivation
    val tokenCipher = Runtime.Cached { 
        secret().cipherBlocking("tokens")
    }
    
    // Use in handlers
    val endpoint = path.get bind HttpHandler {
        val data = tokenCipher().encrypt(payload)
        // ...
    }
}
```

## Troubleshooting

### "Bad tag" exceptions during decryption

**Cause**: The ciphertext was tampered with or encrypted with a different key.

**Solution**: 
- Verify you're using the same variant name for encryption and decryption
- Check that the `SecretBasis` hasn't changed
- Handle as an authentication failure

### Slow password hashing

**Cause**: This is intentional - PBKDF2 uses 100,000 iterations.

**Solution**:
- Use caching (sessions/tokens) to avoid repeated hashing
- Don't call `secureHash()` or `checkAgainstHash()` multiple times per request
- Consider using async/background processing for non-critical paths

### Different signatures for same data

**Cause**: Using different signers or variants.

**Solution**:
- Ensure the same variant is used for signing and verification
- Check that the `SecretBasis` is consistent
- Verify both signer and verifier use the same algorithm (HS256 vs HS512, etc.)

## Further Reading

- [whyoleg/cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin) - Underlying cryptography library
- [NIST Guidelines](https://csrc.nist.gov/publications) - Official cryptography standards
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
