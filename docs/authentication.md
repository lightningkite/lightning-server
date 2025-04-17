# Authentication

Last updated April 17, 2025 (`version-4`)

Authentication is a fundamental concept in Lightning Server, and authentication works the same way across all endpoints. 

We've built authentication out for you, but it is also extremely customizable.  We'll start with the easy one.

## Quick Authentication

Here's a basic example with the simplest auth methods.  

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

## Set up
To set  up authentication you will need at least two endpoints. You will need at least one proof endpoint and the AuthEndpointsForSubject endpoints.

To set up Auth endpoints for subjects create a object called AuthEndpointsForSubject
Modify the parameters to fit your servers needs such as the model for user or other subject.
A subject is the model that is being authenticated in the below example the subject is the User model to change it a different model just the reference to User to a different model
```kotlin
    val userAuth = AuthEndpointsForSubject(
    path("user"), // base url path for these endpoints
    object : Authentication.SubjectHandler<User, UUID> {
        override val name: String get() = "User"  
        override val authType: AuthType get() = AuthType<User>() 
        override val idSerializer: KSerializer<UUID>
            get() = Server.users.info.serialization.idSerializer
        override val subjectSerializer: KSerializer<User>
            get() = Server.users.info.serialization.serializer

        override suspend fun fetch(id: UUID): User = Server.users.info.collection().get(id) ?: throw NotFoundException()
        override suspend fun findUser(property: String, value: String): User? = when (property) {
            "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) ?: run {
                Server.users.info.collection().insertOne(User(username = value,  name = ""))!! // Optional create a new user. You could also just return null throwing an error that the user can't be found
            }
            else -> null
        }

        override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf(RoleCacheKey)

        override suspend fun desiredStrengthFor(result: User): Int =
            if (result.role >= UserRole.Admin) Int.MAX_VALUE else 5
    },
    database = Server.database
)
```



## Authentication options (proofs)
Lightning server uses what are called proofs to prove that a user has access to the server.

You can use one proof or use multiple proofs for multifactor authentication.

The following proofs are available:
 * Email
 * SMS
 * Password
 * Oauth
 * One Time Password
 * Known Device

To use any of the proofs just add the below configurations for each type of proof to your project

### Email:
First make sure you email configuration is set correctly.  [View how to set up email](docs/email.md)

Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
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

In your AuthEndpointsForSubject in the findUser method add the field on the User model you are storing the email and return the user based on that. Or create user if they don't have an account. For example:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "email" -> Server.users.info.collection().findOne(condition { it.email eq value.toEmailAddress() }) ?: run {
                    Server.users.info.collection().insertOne(User(email = value.toEmailAddress(), name = ""))!!
                }
                else -> null
            }
```

### SMS
You need to have a sms client to send sms. [View how to set up sms](docs/sms.md)

In your AuthEndpointsForSubject in the findUser method add the field on the User model you are storing the phone number and return the user based on that. For example:
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

In your AuthEndpointsForSubject in the findUser method add the field on the User model you are storing the phone number and return the user based on that.Or create user if they don't have an account. For example:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "phone" -> Server.users.info.collection().findOne(condition {it.phoneNumber eq value.trim() })
                    ?: Server.users.info.collection().insertOne(User(email = null, phoneNumber = value))!!
                else -> null
            }
```

### Password

Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
```kotlin
    // Endpoints for establishing and validating passwords
    val proofPassword = PasswordProofEndpoints(path("proof/password"), Server.database, Server.cache)

```

In your AuthEndpointsForSubject in the findUser method add the field on the User model you are using to determine which user to prove and return the user based on that. Or create user if they don't have an account. For example:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) ?: run {
                    Server.users.info.collection().insertOne(User(username = value, name = ""))!!
                }
                else -> null
            }
```
### Oauth
You will need to set your OAuth providers in the server:
```kotlin
    val oauthApple = setting<OauthProviderCredentialsApple>("oauthApple", OauthProviderCredentialsApple("", "", "", ""))
    val oauthGoogle = setting<OauthProviderCredentials>("oauthGoogle", OauthProviderCredentials("", ""))
    val oauthMicrosoft = setting<OauthProviderCredentials>("oauthMicrosoft", OauthProviderCredentials("", ""))
```
Then in add the oauthProviders you want to use to your settings.json file
```json
{
  "otherSettings" : "...",
  "oauthMicrosoft" : {
    "id" : "",
    "secret" : ""
  },
  "oauthGoogle" : {
    "id" : "",
    "secret" : ""
  },
  "oauthApple" : {
    "serviceId" : "",
    "teamId" : "",
    "keyId" : "",
    "keyString" : ""
  }
}
```
If you are using terraform put it into your local.auto.tfvars

How to set up a few common OAuth provides
Github 
You can set up a new app for GitHub in your [developer settings](https://github.com/settings/developers). 
1. Get the client ID and a client secret to put into your [setting] parameter.
2. Return URLs are your auth url + /oauth/github/callback

Google
1. You can set up a new Google project in the [Google console](https://console.cloud.google.com)
2. Fill out the [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent)
3. Enable the non-sensitive scopes for '.../auth/userinfo.email' and '.../auth/userinfo.profile'
4. Add an [OAuth 2.0 Client ID](https://console.cloud.google.com/apis/credentials/oauthclient)
5. 'Authorized redirect URIs' are your auth url + /oauth/google/callback

Microsoft
1. You can set up a Microsoft sign-in app in the [Azure Console's Active Directory section](https://portal.azure.com/#view/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/~/RegisteredApps)
2. Note your 'Application (client) ID'.  You'll put that into [setting] as the [OauthProviderCredentials.id].
3. In the API Permissions section, add the permissions 'email' and 'User.Read'.
4. In the Certificates & secrets section, create a new client secret.  Copy out the value and put it into [setting] as the [OauthProviderCredentials.secret].

Apple
1. Get an [Apple Developer Account](https://developer.apple.com)
2. Go to [Certificates, Identities, and Profiles](https://developer.apple.com/account/resources/certificates/list)
3. Add or edit an [App Identifier](https://developer.apple.com/account/resources/identifiers/list/bundleId) to have "Sign in with Apple" capability
4. Add a [Service Identifier](https://developer.apple.com/account/resources/identifiers/list/serviceId) for the server
5. Add Sign In With Apple to said service identifier - Return URLs are your auth url + /oauth/apple/callback
6. Make a [key](https://developer.apple.com/account/resources/authkeys/list) for the server
7. Download the .p8
8. Copy out the contents of the P8 (it's a regular text file)
9. Set the credentials to:
* appId: the App ID above
* serviceId: the Service ID above
* teamId: Your team identifier
* keyId: Your key's ID
* keyString: the contents of the P8 without the begin/end private key annotations


Add the Oauth endpoints to your server
```kotlin
    val proofApple = OauthProofEndpoints(
        path("proof/oauth/apple"),
        provider = OauthProviderInfo.apple,
        credentials = { oauthApple().toOauthProviderCredentials() },
        continueUiAuthUrl = { frontend() + "/login" })
    val proofGoogle = OauthProofEndpoints(
        path("proof/oauth/google"),
        provider = OauthProviderInfo.google,
        credentials = oauthGoogle,
        continueUiAuthUrl = { frontend() + "/login" })
    val proofMicrosoft = OauthProofEndpoints(
        path("proof/oauth/microsoft"),
        provider = OauthProviderInfo.microsoft,
        credentials = oauthMicrosoft,
        continueUiAuthUrl = { frontend() + "/login" })
```

In your AuthEndpointsForSubject in the findUser method add the field on the User model you are using to determine which user to prove and return the user based on that. Or create user if they don't have an account. For example:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) ?: run {
                    Server.users.info.collection().insertOne(User(username = value, name = ""))!!
                }
                else -> null
            }
```

### One Time Password
This is authentication using a Authentication app such as googles authenticator app.

Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
```kotlin
    val proofOtp = OneTimePasswordProofEndpoints(proofPath.path("otp"), Server.database, Server.cache)
```

In your AuthEndpointsForSubject in the findUser method add the field on the User model you are using to determine which user to prove and return the user based on that. Or create user if they don't have an account. For example:
```kotlin
            override suspend fun findUser(property: String, value: String): User? = when (property) {
                "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) ?: run {
                    Server.users.info.collection().insertOne(User(username = value, name = ""))!!
                }
                else -> null
            }
```
In order for a user to use this method they will need to establish a one time password using the etablishOneTimePassword endpoint.

### Known Device
This authentication can allow a key to be stored on a known device and use that to use as another proof. 
Good use case is using a checkbox to not ask again on this device after using another proof to keep user logged in or not have to use an extra proof.
Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
```kotlin
    val knownDeviceProof = KnownDeviceProofEndpoints(path("known-device"), Server.database, Server.cache)
```

# View generated endpoints
You can view the endpoints generated by the server at serveraddress/meta/docs/ or through the generated sdks
