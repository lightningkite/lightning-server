# Authentication

Authentication is a fundamental concept in Lightning Server, and authentication works the same way across all endpoints.
Multiple methods can be checked, and the system is highly customizable while providing sensible defaults.

## Quick Start: Setting Up Authentication

Here's a complete example showing how to set up authentication with email/password and email PIN support:

```kotlin
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import kotlinx.serialization.*
import kotlin.uuid.Uuid

@Queryable
data class User(
    override val _id: Uuid = Uuid.random(),
    val email: String,
    val isSuperUser: Boolean = false
) : HasId<Uuid>

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())

    // Defines the table, registers it, and creates its once-per-deploy prepare task.
    // Invoke it inside a runtime context to get the live table: userTable()
    val userTable = database.registerTable<User>("User")

    // Define your principal type (user authentication)
    object UserAuth : PrincipalType<User, Uuid> {
        override val idSerializer = Uuid.serializer()
        override val subjectSerializer = User.serializer()
        override val name = "User"

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User =
            userTable().get(id) ?: throw NotFoundException()

        context(server: ServerRuntime)
        override suspend fun fetchByProperty(property: String, value: String): User? {
            return when (property) {
                "email" -> {
                    val existing = userTable()
                        .findOne(Condition { it.email eq value })
                    existing ?: userTable()
                        .insertOne(User(email = value))
                }
                else -> super.fetchByProperty(property, value)
            }
        }
    }

    // Set up proof handlers
    val pins = PinHandler(cache, "pins")

    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pin = pins,
        email = email,
        emailTemplate = { to, pin ->
            Email(
                subject = "Your Login Code",
                to = listOf(EmailAddressWithName(to)),
                plainText = "Your PIN is: $pin"
            )
        }
    )

    val proofPassword = path.path("proof").path("password") module
        PasswordProofEndpoints(database, cache)

    // Set up authentication endpoints
    val auth = path.path("auth") module object : AuthEndpoints<User, Uuid>(
        principal = UserAuth,
        database = database,
        cache = cache,
    ) {
        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: User): Int = 5

        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: User): Instant? = null

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: User): Duration? = null
    }
}
```

## Understanding PrincipalType

`PrincipalType` defines how to work with a specific type of authenticated user:

```kotlin
object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()
    override val name = "User"

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        userTable().get(id) ?: throw NotFoundException()

    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): User? {
        return when (property) {
            "email" -> {
                // Find or create user by email
                userTable().findOne(Condition { it.email eq value })
                    ?: userTable().insertOne(User(email = value))
            }
            else -> super.fetchByProperty(property, value)
        }
    }
}
```

## Authentication Methods (Proofs)

Lightning Server supports multiple authentication methods ("proofs"):

### Email PIN

```kotlin
val pins = PinHandler(cache, "pins")

val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
    pin = pins,
    email = email,
    emailTemplate = { to, pin ->
        Email(
            subject = "Your Login Code",
            to = listOf(EmailAddressWithName(to)),
            plainText = "Your PIN is: $pin",
        )
    }
)
```

Requires the `sessions-email` module.

### SMS PIN

```kotlin
val proofSms = path.path("proof").path("sms") module SmsProofEndpoints(
    pin = pins,
    sms = sms
)
```

Requires the `sessions-sms` module.

### Password

```kotlin
val proofPassword = path.path("proof").path("password") module
    PasswordProofEndpoints(database, cache)
```

### Time-Based OTP (TOTP)

Requires the `sessions-totp` module.

```kotlin
val proofOtp = path.path("proof").path("otp") module
    TimeBasedOTPProofEndpoints(database, cache)
```

### WebAuthn

Requires the `sessions-webauthn` module. `WebAuthNProofEndpoints` supports security keys and platform authenticators
such as Touch ID and Windows Hello.

### Backup Codes

```kotlin
val proofBackupCodes = path.path("proof").path("backup-codes") module
    BackupCodeEndpoints(database, cache)
```

Single-use recovery codes. Only their hashes are stored, and a redeemed code is kept, marked as used, as a record of the
login.

### Known Device

```kotlin
val proofDevices = path.path("proof").path("devices") module
    KnownDeviceProofEndpoints(database, cache)
```

## Using Authentication in Endpoints

### Require Authentication

```kotlin
val protectedEndpoint = path.path("protected").get bind ApiHttpHandler(
    auth = UserAuth.require(),
    summary = "Protected Endpoint",
    implementation = { _: Unit ->
        val user = auth.fetch()
        "Hello, ${user.email}!"
    }
)
```

### Optional Authentication

```kotlin
val optionalAuthEndpoint = path.path("optional").get bind ApiHttpHandler(
    auth = UserAuth.require() or AuthRequirement.None,
    summary = "Optional Auth",
    implementation = { _: Unit ->
        val user = authOrNull?.fetch()
        if (user != null) {
            "Hello, ${user.email}!"
        } else {
            "Hello, guest!"
        }
    }
)
```

### Using in ModelInfo

```kotlin
val userInfo = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    tableName = "User",
    permissions = {
        val user = authOrNull?.fetch()
        val self = Condition<User> { it._id eqNn user?._id }
        val admin: Condition<User> = if (user?.isSuperUser == true) Condition.Always else Condition.Never

        ModelPermissions(
            create = Condition.Never,
            read = self or admin,
            update = self or admin,
            delete = admin
        )
    }
)
```

## Authentication Flow

1. **Client requests a PIN**:
    - `POST /proof/email/start` with the email address as a JSON string
    - Server sends an email with the PIN and returns a `key`

2. **Client submits the PIN**:
    - `POST /proof/email/prove` with `{"key": "<key from step 1>", "password": "<PIN>"}`
    - Server returns a signed `Proof`

3. **Client logs in**:
    - `POST /auth/login` with a list of proofs
    - If the proofs are strong enough, the response's `refreshToken` is set. Otherwise it lists the further proof
      `options` the user has and the `strengthRequired`

4. **Client gets an access token**:
    - `POST /auth/token/simple` with the refresh token
    - Server returns a short-lived access token

5. **Client uses the token**:
    - Include `Authorization: Bearer <token>` header in requests

## Customizing Authentication

### Session Expiration

Control when sessions expire:

```kotlin
object : AuthEndpoints<User, Uuid>(principal = UserAuth, database = database, cache = cache) {
    context(server: ServerRuntime)
    override suspend fun requiredProofStrengthFor(subject: User): Int = 5

    context(server: ServerRuntime)
    override suspend fun sessionExpiration(subject: User): Instant? {
        // Sessions expire after 30 days
        return Clock.System.now() + 30.days
    }

    context(server: ServerRuntime)
    override suspend fun sessionStaleAfter(subject: User): Duration? {
        // Require re-authentication after 7 days of inactivity
        return 7.days
    }
}
```

### Proof Strength

Different authentication methods have different "strengths". You can require a minimum strength:

- Email PIN: strength 10
- SMS PIN: strength 5
- Password: strength 10
- TOTP: strength 10
- WebAuthn: strength 10
- OAuth: strength 10
- Known Device: strength 3
- Backup Code: strength 0 when deciding what a user must reach, but each backup code proof counts 10 at login

Only the strongest method for each property counts, and the required strength is capped at what the user's established
methods can reach.

```kotlin
override suspend fun requiredProofStrengthFor(subject: User): Int {
    // Admins need stronger authentication
    return if (subject.isSuperUser) 10 else 5
}
```

## Testing Authentication

When writing tests, you often need to create an `AuthAccess` to simulate an authenticated user without going through the
full authentication flow. Use the `testAuth` extension function on your `PrincipalType`:

```kotlin
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.typed.AuthAccess

@Test
fun testProtectedEndpoint() = Server.testBlocking(settings = {}) {
    // Create and insert a test user; direct database access needs a ServerRuntime
    val user = User(email = "test@example.com", isSuperUser = false)
    execute { userInfo.table().insertOne(user) }

    // Create AuthAccess for testing
    val auth = UserAuth.testAuth(user)
    val access = AuthAccess(auth)

    // Use 'access' wherever AuthAccess is required, e.g. model permissions
}
```

### testAuth Parameters

The `testAuth` function accepts optional parameters:

```kotlin
context(server: Engine)
fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> PrincipalType<SUBJECT, ID>.testAuth(
    subject: SUBJECT,
    issuedAt: Instant = server.clock.now(),
    scopes: Set<GrantedScope> = setOf(GrantedScope.root)
): Authentication<SUBJECT>
```

- **subject**: The user object to authenticate as
- **issuedAt**: When the authentication was issued (defaults to now)
- **scopes**: The granted scopes (defaults to root/full access)

### Testing with Limited Scopes

To test with restricted permissions:

```kotlin
val auth = UserAuth.testAuth(
    subject = user,
    scopes = setOf(GrantedScope("read"), GrantedScope("posts:write"))
)
val access = AuthAccess(auth)
```

### Testing Endpoints Directly

For testing typed endpoints with authentication, use `testAuth` inside a `testBlocking { }` block. `testAuth` only
needs an `Engine`, so it can be called directly in the test body.

```kotlin
@Test
fun testEndpoint() = Server.testBlocking(settings = {}) {
    val user = User(email = "test@example.com")
    execute { Server.userInfo.table().insertOne(user) }

    // Build a test Authentication without going through the real auth flow
    val auth = Server.UserAuth.testAuth(user)

    val result = Server.protectedEndpoint.test(auth = auth, input = Unit)
    assertEquals(expectedValue, result)
}
```

`AuthAccess` is a typealias for `Access<*, *, SUBJECT>` — you rarely need to construct it directly;
the `test(auth = ..., input = ...)` overloads on `ApiHttpHandler` accept `Authentication<USER>` directly.

NEXT: [Typed Endpoints](typed-endpoints.md)
