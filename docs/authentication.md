# Authentication

**OUT OF DATE**

Authentication is a fundamental concept in Lightning Server, and authentication works the same way across all endpoints. 

We've built authentication out for you, but it is also extremely customizable.  We'll start with the easy one.

## Quick Authentication

Here's a most basic example.  This is the absolute minimum required to use the built-in authentication endpoints. 

```kotlin
@file:UseContextualSerialization(UUID::class, Instant::class)

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.cache.*
import com.lightningkite.lightningserver.core.*
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.email.*
import com.lightningkite.lightningserver.settings.*
import kotlinx.serialization.*
import java.time.Instant
import java.util.*
import com.lightningkite.UUID

// Our primary server definition. 
object Server: ServerPathGroup(ServerPath.root) {

    // Settings
    val cache = setting("cache", CacheSettings())
    val database = setting("database", DatabaseSettings())
    val email = setting("email", EmailSettings())

    init {
        // Auth keys
        // A cache key for caching the users role in an access token
        object RoleCacheKey : RequestAuth.CacheKey<User, UUID, UserRole>() {
            override val name: String
                get() = "role"
            override val serializer: KSerializer<UserRole>
                get() = UserRole.serializer()
            override val validFor: Duration
                get() = 5.minutes

            override suspend fun calculate(auth: RequestAuth<User>): UserRole = auth.get().role
        }

        // Prepare models
        prepareModelsShared()
        prepareModelsServerCore()
        com.lightningkite.prepareModelsShared()

        // Authentication level aliases
        Authentication.isDeveloper = authRequired<User> {
            it.role() >= UserRole.Developer
        }
        Authentication.isSuperUser = authRequired<User> {
            it.role() >= UserRole.Root
        }
        Authentication.isAdmin = authRequired<User> {
            it.role() >= UserRole.Admin
        }
    }
}

// Our auth endpoints.
class AuthenticationEndpoints(path: ServerPath): ServerPathGroup(path){

    // Base for pins that are used in email and phone proofs
    val pins = PinHandler(Server.cache, "pins")

    // Endpoints for proofing you own a specific email for authentication
    val proofEmail = EmailProofEndpoints(
        path = path("proof/email"),
        pin = pins,
        email = Server.email,
        emailTemplate = { to, pin ->
            Email(
                subject = "${generalSettings().projectName} Log In",
                to = listOf(EmailLabeledValue(to)),
                html = createHTML(true).let {
                    it.html {
                        emailBase {
                            header("Log In Code")
                            paragraph("Your log in code is:")
                            code(pin)
                            paragraph("If you did not request this code, you can safely ignore this email.")
                        }
                    }
                }
            )
        },
        verifyEmail = { it.toEmailAddress(); true }
    )

    // Endpoints for establishing and verifying otp for a user
    val proofOtp = OneTimePasswordProofEndpoints(path("proof/otp"), Server.database, Server.cache)

    // Endpoints for establishing and validating passwords
    val proofPassword = PasswordProofEndpoints(path("proof/password"), Server.database, Server.cache)

    // Endpoints for establishing a session for a user after generating proofs
    val userAuth = AuthEndpointsForSubject(
        path("user"),
        object : Authentication.SubjectHandler<User, UUID> {
            override val name: String get() = "User"
            override val authType: AuthType get() = AuthType<User>()
            override val idSerializer: KSerializer<UUID>
                get() = Server.users.info.serialization.idSerializer
            override val subjectSerializer: KSerializer<User>
                get() = Server.users.info.serialization.serializer

            override suspend fun fetch(id: UUID): User = Server.users.info.collection().get(id) ?: throw NotFoundException()
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "email" -> Server.users.info.collection().findOne(condition { it.email eq value.toEmailAddress() }) ?: run {
                    Server.users.info.collection().insertOne(User(email = value.toEmailAddress(), name = ""))!!
                }
                "phone" -> users.info.collection().findOne(condition { it.phone eq value })
                "_id" -> Server.users.info.collection().get(UUID.parse(value))
                else -> null
            }

            override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf(RoleCacheKey)

            override suspend fun desiredStrengthFor(result: User): Int =
                if (result.role >= UserRole.Admin) Int.MAX_VALUE else 5
        },
        database = Server.database
    )
}

@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: UUID = UUID.random(),
    val email: EmailAddress,
    val name: String,
    val phone: String,
    val role: UserRole = UserRole.User,
) : HasId<UUID>

@Serializable
enum class UserRole {
    User,
    Admin,
    Developer,
    Root
}
```
## Authentication options
Lightning server uses what are called proofs to prove that a user has access to the server.

You can use one proof or use multiple proofs for multifactor authentication.

The following proofs are available:
 * Email
 * SMS
 * Password
 * Oauth
 * One Time Password
 * Known Device

To use any of the proofs just add the the below configurations for each type of proof to your project

### Email:
First make sure you email configuration is set correctly.  [View how to set up email](docs/email.md)

In your Authentication endpoints add the following:
```kotlin
    // Base for pins that are used in email and phone proofs
    val pins = PinHandler(Server.cache, "pins")
    // Endpoints for proofing you own a specific email for authentication
    val proofEmail = EmailProofEndpoints(
        path = path("proof/email"),
        pin = pins,
        email = Server.email,
        emailTemplate = { to, pin ->
            Email(
                subject = "${generalSettings().projectName} Log In",
                to = listOf(EmailLabeledValue(to)),
                html = createHTML(true).let {
                    it.html {
                        emailBase {
                            header("Log In Code")
                            paragraph("Your log in code is:")
                            code(pin)
                            paragraph("If you did not request this code, you can safely ignore this email.")
                        }
                    }
                }
            )
        },
        verifyEmail = { it.toEmailAddress(); true }
    )
    // Endpoints for establishing and verifying otp for a user
    val proofOtp = OneTimePasswordProofEndpoints(path("proof/otp"), Server.database, Server.cache)
```

In your AuthEndpointsForSubject in the findUser method add the following:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "email" -> Server.users.info.collection().findOne(condition { it.email eq value.toEmailAddress() }) ?: run {
                    Server.users.info.collection().insertOne(User(email = value.toEmailAddress(), name = ""))!!
                }
                else -> null
            }
```

### SMS
You need to have a sms client to send sms. [View how to set up email](docs/sms.md)

In your Authentication endpoints add the following:
```kotlin
    // Base for pins that are used in email and phone proofs
    val pins = PinHandler(Server.cache, "pins")
    val smsProof = SmsProofEndpoints(
        path = path(string = "proof/phone"),
        pin = pins,
        sms = Server.sms,
        smsTemplate = { code -> "Your ${generalSettings().projectName} verification code is: $code" }
    )

```

In your AuthEndpointsForSubject in the findUser method add the following:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "phone" -> Server.users.info.collection().findOne(condition {it.phoneNumber eq value.trim() })
                    ?: Server.users.info.collection().insertOne(User(email = null, phoneNumber = value))!!
                else -> null
            }
```

### Password

In your Authentication endpoints add the following:
```kotlin
    // Endpoints for establishing and validating passwords
    val proofPassword = PasswordProofEndpoints(path("proof/password"), Server.database, Server.cache)

```

### Oauth

### One Time Password

### Known Device

## Raw Authentication

If you wish to implement your own authentication mechanisms, you need only provide your own handler:

```kotlin
Authentication.handler = object: Authentication.Handler<User> {
    suspend fun http(request: HttpRequest): USER? = TODO()
    suspend fun ws(request: WebSocketConnectRequest): USER? = TODO()
    fun userToIdString(user: USER): String = TODO()
    suspend fun idStringToUser(id: String): USER = TODO()
}
```

## `JwtTypedAuthorizationHandler`

A more mid-level option for auth is `JwtTypedAuthorizationHandler`, which allows for multiple types of users over JWT bearer authentication.  This is what the `BaseAuthEndpoints` uses under the hood.

