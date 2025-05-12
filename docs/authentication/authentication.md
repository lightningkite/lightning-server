# Authentication

Last updated April 17, 2025 (`version-4`)

Authentication is a fundamental concept in Lightning Server, and authentication works the same way across all endpoints. 

We've built authentication out for you, it is modular and extremely customizable.  We'll start with an easy one to cover 
the basics.

## Quick Authentication

Here's a basic example with the simplest auth method.

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

    // Settings: Password authentication requires a cache object 
    val cache = setting("cache", CacheSettings())
    val database = setting("database", DatabaseSettings())

    init {

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
    
    val authEndpoints = AuthenticationEndpoints(path("auth"))
}

// Our auth endpoints.
class AuthenticationEndpoints(path: ServerPath): ServerPathGroup(path){
    
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
                
                "email" -> Server.users.info.collection().findOne(condition { it.email eq value.toEmailAddress() }) 
                    ?: run { Server.users.info.collection().insertOne(User(email = value.toEmailAddress(), name = ""))!! }
                
                else -> super.findUser(property, value)
            }

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

## The Basics
Authentication in Lightning Server relies on creating a series of Proofs, then validating these proofs and exchanging 
them for a session. The end user receives a Refresh Token that represents their session. They can use this token to 
authenticate their requests

### Authentication options (Proofs)

Authenticating with a server usually looks like one of the two options:

- A User provides data they know/have, and only they should know/have, such as a password
- A User must re-route data the server sent to an external source back to the server, like an Email OTP.

When a user successfully provides something they know, have, or received to the server, it will return a Proof. 
Lightning server uses Proofs to verify a users identity claims.

A Proof is a signed object created by the server that states what a user proved, and when they proved it.

Example Proof:
```json
{
    "via": "email",
    "strength": 10,
    "property": "email",
    "value": "test@test.com",
    "at": "2025-05-05T22:31:51.822680247Z",
    "signature": "K8SPZorf7caiClSkkgCzTIkOaWg9jC0sic1szharYin6DGwU4AjHlOUeWT7vvTxDDYcpmpj4KI5MLQs0exlNsg=="
}
```

Authenticating can require one Proof or use multiple Proofs for multifactor authentication.

The following proof methods are available:
* Password
* Email OTP
* SMS OTP
* Time-Based OTP
* WebAuthN
* Known Device
* Oauth

You can read in detail about the available proofs [here](proofs.md)

Each Proof has a `strength`. Successful authentication requires a combined strength equal to or greater than the server 
deems necessary. After you have obtained at least one proof you can pass it to the `login` endpoint found in 
[AuthEndpointsForSubject](authentication.md) and the response will either contain a Refresh Token, or a list of other 
Proofs you can use to reach the required strength.

Login response more strength needed Example
```json
{
  "id": "bec9c5e8-b988-4f14-9443-0fe844f6b9e3",
  "options": [
    {
      "method": {
        "via": "email",
        "property": "test@test.com",
        "strength": 10
      },
      "value": null
    },
    {
      "method": {
        "via": "otp",
        "property": null,
        "strength": 5
      },
      "value": null
    }
  ],
  "strengthRequired": 15,
  "session": null
}
```

Once you have enough strength, the `login` response will contain a Refresh Token.  You can read up on 
[Authentication Tokens](authTokens.md) and how to use them.

Login response strength reached Example
```json
{
  "id": "bec9c5e8-b988-4f14-9443-0fe844f6b9e3",
    "options": [
      {
        "method": {
          "via": "otp",
          "property": null,
          "strength": 5
        },
        "value": null
      }
    ],
    "strengthRequired": 10,
    "session": "refresh/User/00000000-0000-0000-a63d-f9176aaa9e30:aYD342fBrWFNJfjRGnj6789WJFWOjfrp"
}
```

## Auth Endpoints for Subjects
Along with the endpoints for proving the subject, we need the handler for retrieving the user and endpoints for the 
final login and token/session management. You must create an instance of the class [AuthEndpointsForSubject](authentication.md).

