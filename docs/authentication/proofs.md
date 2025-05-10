## Authentication Proofs

To use any of the proofs just add the below configurations for each type of proof to your project

### Password

Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
```kotlin
    // Endpoints for establishing and validating passwords
    val proofPassword = PasswordProofEndpoints(path("proof/password"), Server.database, Server.cache)
```

Finding a user when using Password validation is more dynamic than the others. The prove endpoint must pass in the
property they wish to use to identify the User. In your Authentication.SubjectHandler implementation you must implement
which properties you wish to support identification on in the `findUser` method. If your Users have a `username` field 
they will identify with, then you must implement a username property handler. If you want them to be able to identify 
themselves with multiple properties, each property must be handled here. Optionally creating a new user here if they do 
not have an account yet does not work since the Password proof requires a previous setup process with an authenticated 
user.

Username example:
```kotlin
    override suspend fun findUser(property: String, value: String): User? = when (property) {
        
        "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) 
            ?: run { Server.users.info.collection().insertOne(User(username = value, name = ""))!! }

        else -> super.findUser(property, value)
    }
```

#### Email OTP:
First make sure you email configuration is set correctly.  [View how to set up email](../email.md)

Add the following to the server, a good pattern is to add them to your Authentication endpoints:
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
```

In your Authentication.SubjectHandler implementation, in the `findUser` method, add the `email` property handler and
retrieve the user using the value provided. Optionally, you can create a new user if they don't have an account already.

For example:
```kotlin
    override suspend fun findUser(property: String, value: String): User? = when (property) {
        
        "email" -> Server.users.info.collection().findOne(condition { it.email eq value.toEmailAddress() }) 
            ?: run { Server.users.info.collection().insertOne(User(email = value.toEmailAddress(), name = ""))!! }

        else -> super.findUser(property, value)
    }
```

### SMS OTP
You need to have a sms client to send sms. [View how to set up sms](../sms.md)

Add the following to the server, a good pattern is to add them to your Authentication endpoints:
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

In your Authentication.SubjectHandler implementation, in the `findUser` method, add the `phone` property handler and
retrieve the user using the value provided. Optionally, you can create a new user if they don't have an account already.

For example:
```kotlin
    override suspend fun findUser(property: String, value: String): User? = when (property) {
        "phone" -> Server.users.info.collection().findOne(condition {it.phoneNumber eq value.trim() })
            ?: Server.users.info.collection().insertOne(User(email = null, phoneNumber = value))!!

        else -> super.findUser(property, value)
    }
```

### Time-Based OTP
This is authentication using an Authentication app such as Google's Authy app.

Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
```kotlin
    val proofOtp = OneTimePasswordProofEndpoints(proofPath.path("otp"), Server.database, Server.cache)
```

Finding a user when using Time-Based OTP validation is more dynamic than the others. The prove endpoint must pass in the
property they wish to use to identify the User. In your Authentication.SubjectHandler implementation you must implement
which properties you wish to support identification on. If your Users have a `username` field they will identify with,
then you must implement a username property handler. If you want them to be able to identify themselves with multiple
properties, each property must be handled here. Optionally creating a new user here if they do not have an account yet 
does not work since the Time-Based OTP proof requires a previous setup process with an authenticated user.

For example:
```kotlin
    override suspend fun findUser(property: String, value: String): User? = when (property) {
        "username" -> Server.users.info.collection().findOne(condition { it.username eq value })
        else -> null
    }
```
In order for a user to use this method they will need to establish a TOTP device using the establish endpoint in
OneTimePasswordProofEndpoints.

### Known Device
This authentication can allow a key to be stored on a known device and use that to use as proof. This often is
represented as a checkbox to remember the device or "don't require 2fa on this device".

Add the following to the server, a good pattern is to add them to your Authentication endpoints add the following:
```kotlin
    val knownDeviceProof = KnownDeviceProofEndpoints(path("known-device"), Server.database, Server.cache)
```

### WebAuthN
WebAuthN is the standard for authentication with biometrics or hardware security keys. The common names you will see 
used with WebAuthN are Passkeys and SecurityKeys. More information on the standard can be found 
[here](https://webauthn.guide/).

The use WebAuthN in your server you must create an instance of the WebAuthNProofEndpoints class. A good pattern is to 
add them to your Authentication endpoints.
```kotlin
    val proofWebAuthN = WebAuthNProofEndpoints(
        path = proofPath.path("webauthn"),
        database = Server.database,
        cache = Server.cache,
        rpId = "yourdomain.com",
        registrationForUser = { subject, residentKeyPreference ->
            val user = subject as? User ?: throw BadRequestException()
            WebAuthN.Registration.RegistrationOptions(
                user = WebAuthN.PublicKeyCredentialUserEntity(
                    user.name,
                    user._id.toString(),
                    user.email,
                )
            )
        },
        proveOptions = { userId ->
            WebAuthN.Authentication.ProveOptions(userVerification = WebAuthN.GeneralPreference.Preferred)
        }
    )
```
You can customize all the registration options as you wish. There are a lot of options in the standard, and all are 
supported. 

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

In your Authentication.SubjectHandler in the findUser method add the field on the User model you are using to determine which user to prove and return the user based on that. Or create user if they don't have an account. For example:
```kotlin
    override suspend fun findUser(property: String, value: String): User? = when (property) {
        "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) ?: run {
            Server.users.info.collection().insertOne(User(username = value, name = ""))!!
        }
        else -> null
    }
```