# Sessions Module

The sessions system provides comprehensive authentication and session management for Lightning Server applications. It
supports multiple authentication methods, multi-factor authentication, session lifecycle management, and OAuth 2.0
integration.

## Overview

The sessions system consists of these modules:

- **sessions-shared** - Multiplatform data models and client interfaces
- **sessions** - JVM server-side implementation with the password, backup code, and known device proof methods
- **sessions-email** - Email-based authentication (PIN codes)
- **sessions-sms** - SMS-based authentication (PIN codes)
- **sessions-totp** - Time-based one-time passwords from authenticator apps
- **sessions-webauthn** - WebAuthn / passkey authentication
- **sessions-oauth** and **sessions-oauth-shared** - Logging in through OAuth providers

## Key Concepts

### Sessions

A **Session** represents an authenticated user's access to your application. Sessions are created after successful
authentication and contain:

- Unique session ID
- Subject (user) ID
- Secret hash for validation
- Creation and last-used timestamps
- Optional expiration (hard deadline)
- Optional staleness (inactivity timeout)
- IP addresses and user agents (audit trail)
- Authorization scopes
- Optional derivation from parent session

Sessions use **refresh tokens** for long-term access and **access tokens** (JWT or similar) for API requests.

### Proof-Based Authentication

Instead of just "username and password", Lightning Server uses a flexible **proof system**:

1. Each authentication method provides a **Proof** with a strength value
2. Users must accumulate sufficient strength to authenticate
3. Proofs are cryptographically signed to prevent tampering
4. Proofs have expiration times to prevent replay attacks

**Example authentication flows:**

```
Simple (strength 10 required):
- Password proof (strength 10) → Login successful

Two-factor (strength 20 required):
- Password proof (strength 10) + TOTP proof (strength 10) → Login successful

Lower friction on a known device (strength 13 required):
- Password proof (strength 10) + Known device proof (strength 3) → Login successful without a second factor
```

### Available Proof Methods

| Method           | Strength | Description                                     |
|------------------|----------|-------------------------------------------------|
| **Password**     | 10       | Traditional password authentication             |
| **Email**        | 10       | PIN code via email                              |
| **SMS**          | 5        | PIN code via text message                       |
| **TOTP**         | 10       | Time-based codes from authenticator apps        |
| **WebAuthn**     | 10       | Hardware keys, Touch ID, Face ID, Windows Hello |
| **OAuth**        | 10       | Logging in through an external provider         |
| **Backup Code**  | 0 / 10   | Single-use recovery codes; see below            |
| **Known Device** | 3        | A device the user has logged in from before     |

Only the strongest method for each property counts, so two email-based methods do not add up. The strength a user must
reach is capped at the most their established methods could provide.

Backup codes report a strength of 0, so having them never raises the strength a user must reach, but each backup code
proof counts 10 at login, so it can stand in for one lost factor.

## Basic Usage

### 1. Define Your User Model

```kotlin
@Queryable
data class User(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val phone: String? = null,
    val name: String
) : HasId<Uuid>
```

### 2. Create Authentication Endpoints

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())

    // AuthEndpoints subclasses SessionManager; one object provides both session
    // management and authentication endpoints.
    val auth = object : AuthEndpoints<User, Uuid>(
        principal = UserPrincipal,  // your PrincipalType<User, Uuid> implementation
        database = database,
        cache = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? =
            server.clock.now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? =
            7.days

        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 10
    }

    // Add proof methods
    val pinHandler = PinHandler(
        cache = cache,
        keyPrefix = "pins",
        expiration = 10.minutes
    )

    val emailProof = EmailProofEndpoints(
        pin = pinHandler,
        email = email,
        emailTemplate = { email, pin ->
            Email(
                subject = "Your login code",
                to = listOf(EmailAddressWithName(email)),
                plainText = "Your verification code is: $pin"
            )
        }
    )

    // PasswordProofEndpoints registers its own "PasswordSecret" table and hashes
    // passwords on write; you only hand it the database and cache.
    val passwordProof = PasswordProofEndpoints(database, cache)

    // Include in your server
    init {
        path.path("auth") include auth
        path.path("auth").path("proof").path("email") include emailProof
        path.path("auth").path("proof").path("password") include passwordProof
    }
}
```

### 3. Client-Side Login Flow

```kotlin
// 1. Start email verification
val tempKey = emailProof.beginEmailOwnershipProof("user@example.com")

// 2. User enters PIN from email
val emailProof = emailProof.proveEmailOwnership(
    FinishProof(key = tempKey, password = "123456")
)

// 3. Optional: Get password proof
val passwordProof = passwordProof.provePasswordOwnership(
    IdentificationAndPassword(
        type = "User",
        property = "email",
        value = "user@example.com",
        password = "correct-password"
    )
)

// 4. Log in with accumulated proofs
val result = authEndpoints.logInV2(
    LogInRequest(
        proofs = listOf(emailProof, passwordProof),
        label = "Mobile App",
        scopes = setOf(GrantedScope.root),
        expires = Clock.System.now() + 30.days
    )
)

// 5. Exchange refresh token for access token
val accessToken = authEndpoints.getTokenSimple(result.refreshToken!!)

// 6. Use access token for API requests
// Include in Authorization header: "Bearer $accessToken"
```

## Advanced Features

### Sub-Sessions

Create derived sessions with reduced privileges, e.g. a short-lived, read-only token for a report export running
alongside the user's own session:

```kotlin
val limitedToken = authEndpoints.subsession(
    SubSessionRequest(
        label = "Report Export",
        scopes = setOf(GrantedScope("read:profile"), GrantedScope("read:posts")),
        expires = Clock.System.now() + 1.hours
    )
)
```

A sub-session:

- gets the parent session's scopes when `scopes` is omitted (the default, root, means "whatever the parent has"),
- may only request scopes the parent session already has (otherwise 403),
- keeps the parent's authentication time, so it cannot pass a `maxAge` requirement (such as the 10-minute re-authentication gate on credential management) that the parent could not,
- is terminated when its parent is terminated or deleted through the session table (not `baseTable()` or raw database writes), and so on down the chain.

Because of that last point, a sub-session is not a way to hand out access that should outlive the user's login. For a
long-lived third-party integration, log in a separate session for it (or use OAuth), so the user logging out of their
browser doesn't cut it off.

### Known Device Authentication

Reduce friction on devices the user has logged in from before:

```kotlin
// After successful login on new device
val deviceSecret = knownDeviceProof.establishKnownDeviceV2()
// Store deviceSecret.secret securely on the client

// On subsequent logins
val knownDeviceProof = knownDeviceProof.proveKnownDevice(deviceSecret.secret)
// Adds strength 3 to the login, alongside the user's other proofs
```

### TOTP Setup

```kotlin
// User is already authenticated
val qrCodeUrl = totpProof.establishOneTimePassword(
    EstablishTotp(label = "My iPhone")
)
// Display QR code for user to scan with authenticator app

// User scans and enters first code to confirm
totpProof.confirmOneTimePassword("123456")
```

Establishing a new authenticator does not disable the existing one. It stays active until the new one is confirmed, so
abandoning setup never locks a user out.

### Session Management

```kotlin
// Get current user
val user = authEndpoints.getSelf()

// List all sessions (via ModelInfo endpoints)
val sessions = userAuth.sessionInfo.list()

// Terminate current session (logout)
authEndpoints.terminateSession()

// Terminate specific session (logout from another device)
authEndpoints.terminateSession(sessionId)
```

Sessions are marked terminated, never deleted, and nothing can delete one through the REST endpoints, not even a
superuser. Terminating a session also terminates every sub-session derived from it. Access tokens that were already
issued stay valid until they expire.

### Observing Authentication Events

`sessions` reports what happens during authentication as `AuthEvent`s: sessions created, terminated, and rejected, and
proofs issued, accepted, and rejected. Install an `AuthEventListener` to receive them:

```kotlin
object Server : ServerBuilder() {
    init {
        install(object : AuthEventListener {
            context(runtime: ServerRuntime)
            override suspend fun onEvent(event: AuthEvent) {
                if (event is AuthEvent.ProofCredentialRejected) runtime.logger.warn { "Rejected proof for ${event.principal}" }
            }
        })
    }
}
```

A listener's exception is logged and does not affect the request that raised the event. The audit module's
`AuthEventLog` is a listener that records these events to the database.

### Hooks on Proof Tables

The password, TOTP, known device, and WebAuthn endpoints take a `signals` parameter that decorates their secrets table,
for example to observe changes. `PasswordProofEndpoints` also takes `evaluatePassword`, called with each new password
before it is hashed, so you can reject weak ones:

```kotlin
val passwordProof = PasswordProofEndpoints(
    database, cache,
    evaluatePassword = { info ->
        if (info.password.length < 12) throw BadRequestException("Passwords must be at least 12 characters")
    },
)
```

## Security Considerations

1. **Always use HTTPS** - Authentication credentials must be transmitted securely
2. **Hash secrets server-side** - Never store plaintext passwords or device secrets
3. **Implement rate limiting** - Prevent brute force and SMS/email bombing
4. **Short-lived access tokens** - Use refresh tokens for long-term access
5. **Audit trails** - Sessions track IPs and user agents for security monitoring
6. **Proof expiration** - Proofs expire quickly to prevent replay attacks
7. **Scope restrictions** - Use minimal scopes for sub-sessions and OAuth clients

## Configuration

### PIN Handler

```kotlin
val pinHandler = PinHandler(
    cache = cache,
    keyPrefix = "pins",     // Namespaces this handler's cache entries
    length = 6,             // PIN length
    expiration = 10.minutes, // How long PINs remain valid
    maxAttempts = 5,        // Guesses allowed per PIN
)
```

### Token Format

By default, sessions use compact signed tokens. For JWT:

```kotlin
val auth = object : AuthEndpoints<User, Uuid>(
    principal = UserPrincipal,
    database = database,
    cache = cache,
    tokenFormat = Runtime { JwtTokenFormat() }
) { /* ... */ }
```

### Authentication Requirements

Customize required strength per user:

```kotlin
context(_: ServerRuntime)
override suspend fun requiredProofStrengthFor(subject: User): Int {
    return when {
        subject.isAdmin -> 20 // Admins need a second factor
        else -> 10 // Default
    }
}
```

## Testing

Use mock services for unit testing:

```kotlin
class AuthTest {
    @Test
    fun testLogin() = Server.testBlocking(settings = {}) {
        val result = Server.auth.login.test(
            auth = null,
            input = listOf(/* proofs */)
        )

        assertNotNull(result.refreshToken)
    }
}
```

## Additional Modules

- **sessions-email** - Add with `implementation("com.lightningkite.lightningserver:sessions-email:$version")`
- **sessions-sms** - Add with `implementation("com.lightningkite.lightningserver:sessions-sms:$version")`
- **sessions-totp** - Add with `implementation("com.lightningkite.lightningserver:sessions-totp:$version")`
- **sessions-webauthn** - Add with `implementation("com.lightningkite.lightningserver:sessions-webauthn:$version")`

The email and SMS modules require appropriate service configurations (Email/SMS providers) in your settings.

## See Also

- [Authentication Module](./authentication.md) - Core auth concepts
- [Database Module](./database.md) - Storing sessions and secrets
- [Settings](./settings.md) - Configuring email/SMS services
