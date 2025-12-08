# Proofs Package

This package contains the proof-based authentication system for Lightning Server, supporting multi-factor authentication with pluggable proof methods.

## Authentication Concept

Authentication in Lightning Server uses **proofs** - verified evidence of identity. Each proof method (password, SMS, email, TOTP, WebAuthn, etc.) provides a proof with an associated strength value. Users must accumulate sufficient proof strength to authenticate.

Example: A system requiring strength 10 might accept:
- Password (strength 10) alone
- Email (strength 5) + TOTP (strength 5)
- SMS (strength 5) + Known Device (strength 5)

## Files

### proofModels.kt
Core proof data models:
- **Proof** - Cryptographically signed authentication evidence
- **ProofOption** - Available authentication method for a user
- **ProofMethodInfo** - Descriptor for an authentication method
- **Identification** - User identification without credentials
- **IdentificationAndPassword** - Traditional username/password input
- **FinishProof** - Complete a multi-step authentication challenge
- **AuthRequirements** - Authentication requirements for the system
- **KnownDeviceOptions** - Configuration for "remember this device" feature
- **KnownDeviceSecretAndExpiration** - Device recognition credentials with expiration

### OtpHashAlgorithm.kt
Enum defining HMAC algorithms for TOTP:
- **SHA1** - Recommended default, universally compatible
- **SHA256** - Limited compatibility (experimental)
- **SHA512** - Very limited compatibility (experimental)

### WebAuthN.kt
Complete WebAuthn (Web Authentication API) implementation:
- **WebAuthNCredential** - Stored credential for hardware/platform authenticators
- **WebAuthN** object containing:
  - **Registration** - Models for credential registration
  - **Authentication** - Models for authentication ceremony
  - Enums for algorithm support, attestation preferences, transports, etc.

Supports FIDO2 security keys, Touch ID, Face ID, Windows Hello, and other WebAuthn authenticators.

### AuthClientEndpoints.kt
Client-side interface for authentication operations:
- Login with proofs
- Check proof sufficiency
- Get/refresh tokens
- Session management (create, terminate, subsession)
- Get authenticated user profile

### ProofClientEndpoints.kt
Sealed interface hierarchy for proof method-specific operations:
- **Sms** - SMS/phone number verification
- **Email** - Email address verification
- **TimeBasedOTP** - TOTP authenticator app support
- **Password** - Traditional password authentication
- **BackupCode** - Recovery codes for account access
- **WebAuthN** - Hardware/platform authenticator support
- **KnownDevice** - Device recognition for reduced friction

Each interface defines operations for both establishing credentials (requires auth) and proving credentials (during login).

### LiveAuthClientEndpoints.kt
HTTP client implementation of AuthClientEndpoints using the Fetcher abstraction.

### LiveProofClientEndpoints.kt
HTTP client implementations for all ProofClientEndpoints interfaces.

## Sub-packages

### oauth/
OAuth 2.0 models and support (see `oauth/index.md`)
