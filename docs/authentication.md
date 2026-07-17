# Authentication

Last updated January 2025 (`version-5`)

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

@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: Uuid = Uuid.random(),
    override val email: String,
    override val hashedPassword: String = "",
    val isSuperUser: Boolean = false
) : HasId<Uuid>, HasEmail, HasPassword

object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())

    // Define your principal type (user authentication)
    object UserAuth : PrincipalType<User, Uuid> {
        override val idSerializer = Uuid.serializer()
        override val subjectSerializer = User.serializer()
        override val name = "User"

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User =
            database().table<User>().get(id) ?: throw NotFoundException()

        context(server: ServerRuntime)
        override suspend fun fetchByProperty(property: String, value: String): User? {
            return when (property) {
                "email" -> {
                    val existing = database().table<User>()
                        .findOne(condition { it.email eq value })
                    existing ?: database().table<User>()
                        .insertOne(User(email = value))
                }
                else -> super.fetchByProperty(property, value)
            }
        }
    }

    // Set up proof handlers
    val pins = PinHandler(cache, "pins")

    val proofEmail = path.path("proof").path("email") module EmailProofEndpoints(
        pins = pins,
        email = email,
        emailBuilder = { to, pin ->
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
        database = database
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
        database().table<User>().get(id) ?: throw NotFoundException()

    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): User? {
        return when (property) {
            "email" -> {
                // Find or create user by email
                database().table<User>().findOne(condition { it.email eq value })
                    ?: database().table<User>().insertOne(User(email = value))
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
    pins = pins,
    email = email,
    emailBuilder = { to, pin ->
        Email(
            subject = "Your Login Code",
            to = listOf(EmailAddressWithName(to)),
            plainText = "Your PIN is: $pin",
            html = "<h1>Your PIN is: $pin</h1>"
        )
    }
)
```

### SMS PIN

```kotlin
val proofSms = path.path("proof").path("sms") module SmsProofEndpoints(
    pins = pins,
    sms = sms
)
```

### Password

```kotlin
val proofPassword = path.path("proof").path("password") module
    PasswordProofEndpoints(database, cache)
```

### Time-Based OTP (TOTP)

```kotlin
val proofOtp = path.path("proof").path("otp") module
    TimeBasedOTPProofEndpoints(database, cache)
```

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
    permissions = {
        val user = authOrNull?.fetch()
        val self = condition<User> { it._id eq user?._id }
        val admin = if (user?.isSuperUser == true)
            condition { it.always }
        else
            condition { it.never }

        ModelPermissions(
            create = condition { it.never },
            read = self or admin,
            update = self or admin,
            delete = admin
        )
    }
)
```

## Authentication Flow

1. **Client requests proof**:
    - `POST /proof/email/request` with `{"property": "email", "value": "user@example.com"}`
    - Server sends email with PIN

2. **Client submits proof**:
    - `POST /proof/email/prove` with `{"property": "email", "value": "user@example.com", "proof": "123456"}`
    - Server returns a proof token

3. **Client exchanges proof for session**:
    - `POST /auth/login-anonymous` with proof token
    - Server returns JWT session token

4. **Client uses session**:
    - Include `Authorization: Bearer <token>` header in requests

## Customizing Authentication

### Session Expiration

Control when sessions expire:

```kotlin
object : AuthEndpoints<User, Uuid>(principal = UserAuth, database = database) {
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

- Email PIN: strength 5
- SMS PIN: strength 5
- Password: strength 5
- TOTP: strength 10
- Known Device: strength 15

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

// Inside your test (within a ServerRuntime context):
@Test
fun testProtectedEndpoint() {
    TestHelper.testServer {
        runBlocking {
            // Create and insert a test user
            val user = User(
                email = "test@example.com",
                hashedPassword = "hashed_password",
                isSuperUser = false
            )
            userInfo.table().insertOne(user)

            // Create AuthAccess for testing
            val auth = UserAuth.testAuth(user)
            val access = AuthAccess(auth)

            // Use 'access' wherever AuthAccess is required
            // e.g., for AI tools, model permissions, etc.
        }
    }
}
```

### testAuth Parameters

The `testAuth` function accepts optional parameters:

```kotlin
context(server: ServerRuntime)
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

For testing typed endpoints with authentication, use `testAuth` inside a `Server.test { }` block.
`testAuth` is a `context(server: ServerRuntime)` extension on `PrincipalType`, so it is only
callable where a `ServerRuntime` is in context (i.e., inside `test { }`).

```kotlin
@Test
fun testEndpoint() {
    Server.test(settings = {}) {
        runBlocking {
            val user = User(email = "test@example.com", hashedPassword = "...")
            Server.userInfo.table().insertOne(user)

            // Build a test Authentication without going through the real auth flow
            val auth = Server.userPrincipal.testAuth(user)

            val result = Server.protectedEndpoint.test(auth = auth, input = Unit)
            assertEquals(expectedValue, result)
        }
    }
}
```

`AuthAccess` is a typealias for `Access<*, *, SUBJECT>` — you rarely need to construct it directly;
the `test(auth = ..., input = ...)` overloads on `ApiHttpHandler` accept `Authentication<USER>` directly.

NEXT: [Typed Endpoints](typed-endpoints.md)
