# Encryption Package

The `com.lightningkite.lightningserver.encryption` package provides cryptographic primitives for secure server applications.

## Files

### SecretBasis.kt
Core master secret class that serves as the basis for all key derivation. Uses a 512-bit random secret and HMAC-SHA512 for deriving application-specific keys. Each variant produces cryptographically independent keys suitable for different purposes (encryption, signing, etc.).

**Key Classes:**
- `SecretBasis` - Master secret for key derivation

**Key Functions:**
- `derive()` / `deriveBlocking()` - Derive raw key material from variant strings
- `deriveKey()` / `deriveKeyBlocking()` - Derive typed cryptographic keys

### SecretBasis.ciphers.kt
Extension functions for deriving AES cipher keys from SecretBasis. Supports multiple AES modes with the default being AES-GCM (recommended for its authenticated encryption properties).

**Key Functions:**
- `cipher()` / `cipherBlocking()` - Get AES-GCM cipher (default, recommended)
- `AES_GCM()` / `AES_GCM_Blocking()` - Derive AES-GCM keys explicitly
- `AES_CBC()` / `AES_CBC_Blocking()` - Derive AES-CBC keys (confidentiality only)
- `AES_CTR()` / `AES_CTR_Blocking()` - Derive AES-CTR keys (confidentiality only)

**Key Classes:**
- `AES_KeySize` - Enum for AES key sizes (128, 192, 256 bits)

### SecretBasis.signers.kt
Extension functions for deriving HMAC-based signature keys from SecretBasis. Provides JWT-compatible signing algorithms (HS256, HS384, HS512).

**Key Functions:**
- `signer()` / `signerBlocking()` - Get HMAC-SHA512 signer (default, recommended)
- `HS256()` - Derive HMAC-SHA256 signer (JWT: HS256)
- `HS384()` - Derive HMAC-SHA384 signer (JWT: HS384)
- `HS512()` - Derive HMAC-SHA512 signer (JWT: HS512)
- `HMAC()` / `HMAC_Blocking()` - Derive raw HMAC keys

### Signer.kt
Interface and implementations for cryptographic signature generation and verification. Supports both symmetric (HMAC, CMAC) and asymmetric (ECDSA, RSA) signature algorithms.

**Key Interfaces:**
- `Signer` - Base interface for signature operations

**Key Implementations:**
- `Signer.HMAC` - HMAC-based signing (symmetric)
- `Signer.CMAC` - CMAC-based signing (symmetric, AES)
- `Signer.ECDSA` - Elliptic curve signing (asymmetric)
- `Signer.RSA_PSS` - RSA-PSS signing (asymmetric)
- `Signer.RSA_PKCS1` - RSA-PKCS1 signing (asymmetric, legacy)

**Key Functions:**
- `sign()` / `signBlocking()` - Generate signatures
- `verify()` / `verifyBlocking()` - Verify signatures
- `ES256()`, `ES384()`, `ES512()` - ECDSA signer helpers

### SecureHash.kt
Password hashing utilities using PBKDF2-HMAC-SHA512. Designed specifically for secure password storage with automatic salting and high iteration counts (100,000 iterations) to resist brute-force attacks.

**Key Functions:**
- `secureHash()` - Hash a password with random salt
- `checkAgainstHash()` - Verify a password against stored hash

**Performance Note:** These functions are intentionally slow (~100-200ms) and should only be called once per authentication attempt.

## Usage Examples

### Basic Encryption
```kotlin
val basis = SecretBasis()
val cipher = basis.cipher("user-data")
val encrypted = cipher.encrypt("secret".encodeToByteArray())
val decrypted = cipher.decrypt(encrypted)
```

### Digital Signatures
```kotlin
val basis = SecretBasis()
val signer = basis.signer("api-tokens")
val signature = signer.sign("message".encodeToByteArray())
val isValid = signer.verify("message".encodeToByteArray(), signature)
```

### Password Hashing
```kotlin
// During registration
val hash = password.secureHash()
database.saveUser(User(email, hash))

// During login
val user = database.getUser(email)
val isValid = password.checkAgainstHash(user.passwordHash)
```

## Security Recommendations

1. **Use AES-GCM for encryption** - Provides both confidentiality and authentication
2. **Use HS512 for signatures** - Strongest HMAC variant
3. **Rate-limit password verification** - Prevent brute-force attacks
4. **Use unique variants** - Different purposes should use different variant strings
5. **Cache derived keys** - Use `Runtime.Cached` to avoid repeated derivation

## See Also

- [Full Encryption Documentation](../../../../docs/encryption.md)
- [Authentication Documentation](../../../../docs/authentication.md)
- [Settings Documentation](../../../../docs/settings.md)
