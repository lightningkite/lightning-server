# Authentication

Authentication is a fundamental concept in Lightning Server, and authentication works the same way across all endpoints.  Multiple methods can be checked.

We've built authentication out for you, but it is also extremely customizable.  We'll start with the easy one.

## Quick Authentication

Here's a most basic example.  This is the absolute minimum required to use the built-in authentication endpoints with magic-link / PIN auth.

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

// Our primary server definition.
object Server : ServerPathGroup(ServerPath.root) {
    val settingName = setting(name = "settingName", default = "defaultValue")
    val database = setting(name = "database", default = DatabaseSettings())
    val cache = setting(name = "cache", default = CacheSettings())
    val email = setting(name = "email", default = EmailSettings())
    val jwt = setting(name = "jwt", default = JwtSigner())

    val auth = AuthEndpoints(path("auth"))
}

// Our auth endpoints.
class AuthEndpoints(path: ServerPath): ServerPathGroup(path) {
    // You'll make one of these if you set up auto-rest for users regardless.
    val userModelInfo = ModelInfo(
        getCollection = { Server.database().collection<User>() },
        forUser = { user: User -> this }
    )
    // Information about how to access users, including what to do if a user is not found using a certain email.
    // In this case, a user is simply created if it does not exist.
    val userAccess = userModelInfo.userEmailAccess { User(email = it) }
    // The basic auth endpoint information.  Required no matter what kind of authentication you're doing.
    val baseAuth = BaseAuthEndpoints(
        path = path("auth"),
        userAccess = userAccess,
        jwtSigner = Server.jwt
    )
    // Authenticates user via email magic link / PIN
    val emailAuth = EmailAuthEndpoints(
        base = baseAuth,
        emailAccess = userAccess,
        cache = Server.cache,
        email = Server.email,
        // There are options in here for customizing the email that's sent, as well as what kinds of PINs are generated.
    )
}

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String,
) : HasId<UUID>, HasEmail
```

## Raw Authentication

If you wish to implement your own authentication mechanisms, you need only provide your own handler:

```kotlin
Authentication.handler = object: Authentication.Handler<User> {
    suspend fun http(request: HttpRequest): USER? = TODO()
    suspend fun ws(request: WebSockets.ConnectEvent): USER? = TODO()
    fun userToIdString(user: USER): String = TODO()
    suspend fun idStringToUser(id: String): USER = TODO()
}
```

## `JwtTypedAuthorizationHandler`

A more mid-level option for auth is `JwtTypedAuthorizationHandler`, which allows for multiple types of users over JWT bearer authentication.  This is what the `BaseAuthEndpoints` uses under the hood.

