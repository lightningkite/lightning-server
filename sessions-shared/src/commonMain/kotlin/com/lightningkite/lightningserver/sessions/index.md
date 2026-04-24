# Sessions Package

This package contains shared multiplatform models and interfaces for the Lightning Server sessions system.

## Files

### sessionModels.kt

Core session data models:

- **SubSessionRequest** - Request to create a derived session with reduced privileges
- **Session** - Active authentication session with lifecycle management
- **LogInRequest** - Request to authenticate and create a new session
- **IdAndAuthMethods** - Response containing user ID and available authentication options
- **ProofsCheckResult** - Result of validating authentication proofs before login

### AuthSecrets.kt

Authentication credential storage models:

- **TotpSecret** - Time-based One-Time Password (TOTP) credentials for authenticator apps
- **PasswordSecret** - Hashed password storage with optional hints
- **KnownDeviceSecret** - "Remember this device" credentials
- **EstablishPassword** - Input model for setting/changing passwords
- **EstablishTotp** - Input model for setting up TOTP

All secret models include tracking for establishment time, last use, expiration, and disabled status.

## Sub-packages

### proofs/

Contains models and interfaces for the proof-based authentication system. See `proofs/index.md` for details.
