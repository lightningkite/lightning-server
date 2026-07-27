# Sessions Module

The sessions system provides comprehensive authentication and session management for Lightning Server applications. It
supports multiple authentication methods, multi-factor authentication, session lifecycle management, and OAuth 2.0
integration.

## Overview

The sessions system consists of four modules:

- **sessions-shared** - Multiplatform data models and client interfaces
- **sessions** - JVM server-side implementation with proof methods
- **sessions-email** - Email-based authentication (magic links, PIN codes)
- **sessions-sms** - SMS-based authentication (PIN codes)

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

Two-factor (strength 10 required):
- Email proof (strength 5) + TOTP proof (strength 5) → Login successful

Stepped-down on known device (strength 10 required):
- Password proof (strength 10) + Known device (reduces requirement to 5) → Login successful with less friction
```

### Available Proof Methods

| Method           | Strength | Description                                     |
|------------------|----------|-------------------------------------------------|
| **Password**     | 10       | Traditional password authentication             |
| **Email**        | 5        | PIN code or magic link via email                |
| **SMS**          | 5        | PIN code via text message                       |
| **TOTP**         | 5        | Time-based codes from authenticator apps        |
| **WebAuthn**     | 10       | Hardware keys, Touch ID, Face ID, Windows Hello |
| **Backup Code**  | 10       | Single-use recovery codes                       |
| **Known Device** | N/A      | Reduces strength requirement on trusted devices |

## Basic Usage

### 1. Define Your User Model

```kotlin
@Serializable
@GenerateDataClassPaths
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
    val email = setting("email", Email.Settings())

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
        override suspend fun requiredProofStrengthFor(subject: User): Int = 100
    }

    // Add proof methods
    val pinHandler = PinHandler(
        cache = cache,
        pinGenerator = { BadWordList.avoidingPin(length = 6) },
        expiration = 10.minutes
    )

    val emailProof = EmailProofEndpoints(
        pin = pinHandler,
        email = email,
        emailTemplate = { email, pin ->
            Email(
                subject = "Your login code",
                to = listOf(EmailAddress(email)),
                plainText = "Your verification code is: $pin"
            )
        }
    )

    val passwordProof = PasswordProofEndpoints(
        table = database.table(passwordSecretTable),
        getSubjectId = { it.email },
        hash = { it.secureHash() }
    )

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

Create derived sessions with reduced privileges:

```kotlin
val limitedToken = authEndpoints.subsession(
    SubSessionRequest(
        label = "Third-Party Integration",
        scopes = setOf(GrantedScope("read:profile"), GrantedScope("read:posts")),
        expires = Clock.System.now() + 1.hours
    )
)
```

### Known Device Authentication

Reduce friction on trusted devices:

```kotlin
// After successful login on new device
val deviceSecret = knownDeviceProof.establishKnownDeviceV2()
// Store deviceSecret.secret securely on the client

// On subsequent logins
val knownDeviceProof = knownDeviceProof.proveKnownDevice(deviceSecret.secret)
// This reduces the required proof strength
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
    pinGenerator = { BadWordList.avoidingPin(length = 6) }, // Avoids offensive words
    expiration = 10.minutes // How long PINs remain valid
)
```

### Token Format

By default, sessions use compact signed tokens. For JWT:

```kotlin
val userAuth = object : SessionManager<User, Uuid>(
    principal = userPrincipal,
    database = database,
    tokenFormat = Runtime { JwtTokenFormat(secretBasis()) }
) { /* ... */ }
```

### Authentication Requirements

Customize required strength per user:

```kotlin
context(ServerRuntime)
override suspend fun requiredProofStrengthFor(user: User): Int {
    return when {
        user.isAdmin -> 15 // Admins need stronger auth
        user.email.endsWith("@trusted-domain.com") -> 5 // Trusted users need less
        else -> 10 // Default
    }
}
```

## Testing

Use mock services for unit testing:

```kotlin
class AuthTest {
    @Test
    fun testLogin() = runBlocking {
        val engine = LocalEngine(Server.build())

        // Test login endpoint
        val response = Server.auth.login.test(
            engine = engine,
            input = listOf(/* proofs */)
        )

        assertEquals(HttpStatus.OK, response.status)
    }
}
```

## Additional Modules

- **sessions-email** - Add with `implementation("com.lightningkite.lightningserver:sessions-email:$version")`
- **sessions-sms** - Add with `implementation("com.lightningkite.lightningserver:sessions-sms:$version")`

Both modules require appropriate service configurations (Email/SMS providers) in your settings.

## See Also

- [Authentication Module](./authentication.md) - Core auth concepts
- [Database Module](./database.md) - Storing sessions and secrets
- [Settings](./settings.md) - Configuring email/SMS services
