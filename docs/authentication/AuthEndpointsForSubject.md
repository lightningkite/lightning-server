## AuthEndpointsForSubject

The constructor for AuthEndpointsForSubject is rather simple with only a few arguments. The `SubjectHandler` however is 
much more complicated.
```kotlin
    val userAuth = AuthEndpointsForSubject(
    path = path("user"), // base url path for these endpoints,
    database = Server.database,
    tokenFormat = { PrivateTinyTokenFormat() },
    handler = object : Authentication.SubjectHandler<User, UUID> {
        override val name: String get() = "User" 
        override val authType: AuthType get() = AuthType<User>() 
        override val idSerializer: KSerializer<UUID>
            get() = Server.users.info.serialization.idSerializer
        override val subjectSerializer: KSerializer<User>
            get() = Server.users.info.serialization.serializer

        override suspend fun fetch(id: UUID): User = Server.users.info.collection().get(id) ?: throw NotFoundException()
        override suspend fun findUser(property: String, value: String): User? = when (property) { // How to retrieve a subject given a property and a value. 
            // You will want to add checks for several options depending on how many Proof methods you use.
            "username" -> Server.users.info.collection().findOne(condition { it.username eq value }) ?: run {
                Server.users.info.collection().insertOne(User(username = value,  name = ""))!! // Optional create a new user. You could also just return null throwing an error that the user can't be found
            }
            else -> super.findUser(property, value)
        }

        override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf()
        
        override suspend fun desiredStrengthFor(result: User): Int =
            if (result.role >= UserRole.Admin) Int.MAX_VALUE else 5
    }
)
```
### Token Format
The tokenFormat takes a TokenFormatter. Three TokenFormatters are provided with Lightning Server, but you can create
your own if you wish. You can learn about the three available [here](authTokens.md)


### Subject Handler
The SubjectHandler is where the connection between the authentication endpoints, and your subject are done. This is 
where we define how to retrieve the subject, how to serialize the subject, how much strength is required for 
authentication, and any caching for the subject.

#### Name
A unique name for this handler. Usually just the subject's class name, but may be whatever you please.

#### AuthType
The AuthType for the subject

#### ID Serializer
The serializer of your subject's _id Type

#### Subject Serializer
The serializer of your subject type

#### Fetch
How to retrieve a single instance of the Subject Type

#### Find User
This is where the configurations happen for your proofs. There are various properties that the proofs will use to 
identify/validate the Subject. You must provide a handler for each of the properties that you expect to come through 
this endpoint. Given the `property` you must retrieve an instance of the Subject that has the `value`. 

Here is an example for a SubjectHandler designed for a username/password proof and an email otp proof.
```kotlin
    override suspend fun findUser(property: String, value: String): User? = when (property) {
        "username" -> Server.users.info.collection().findOne(condition { it.username eq value })
        "email" -> Server.users.info.collection().findOne(condition { it.email eq value })
        else -> super.findUser(property, value)
    }
```
You should call out to super if the `property` provided is not any you have accounted for.

#### Permit Masquerade
The concept of masquerading as another user is built into Lightning Server authentication. This is where you can 
restrict the use of masquerading to specific users. By default this returns false, preventing any masquerading.

#### Get Session Expiration
This allows you to set an upper limit for how long a session may last. If null, then sessions can have no expiration.

#### Desired Strength For subject
This is where you define the strength required for a Subject to authenticate with your server. This is how you can 
handle MFA for each instance of you subject. It is not possible to produce a number too large here. If the number 
returned is greater than the sum of all available proofs, then the user will allowed in when all available proofs are 
provided.

#### Known Cache Types
The SubjectHandler can accept a set of CacheKeys. CacheKeys will be used to embed data into the Access Token a Subject 
creates with their Refresh Token. This cached data can be whatever you want it to be. Most common it is the Subject's 
permissions. When the Access token is verified and parsed, this data will be available immediately, no other database or 
external call required. This can drastically speed up the request times, especially if your permission require extensive 
calculations.

CacheKey example:
```kotlin
    val roleCache = object RoleCacheKey : RequestAuth.CacheKey<User, UUID, UserRole>() {
        override val name: String
            get() = "role"
        override val serializer: KSerializer<UserRole>
            get() = UserRole.serializer()
        override val validFor: Duration
            get() = 5.minutes

        override suspend fun calculate(auth: RequestAuth<User>): UserRole = auth.get().role
    }


    //Inside Subject Handler
    override val knownCacheTypes: List<RequestAuth.CacheKey<User, UUID, *>> = listOf(roleCache)

    //Retrieving a cached value in a request
    val role = auth.get(roleCache)
```

If you create a CacheKey, but do not provide it to knownCacheTypes, it will not be stored in the Access Token. If the 
value is not cached in the Access Token, calling get with the CacheKey will still retrieve the value using the calculate 
function. It will cache the value for that key during the duration of that request, so you can call get multiple times 
and only the first time will make the expensive calculate call.


## Endpoints

### Login
The `login` endpoints takes a list of proofs and returns either a list of available proofs for further strength, or a 
Refresh Token

### Login2
The `login2` endpoints allows for further configuration of the session that will be created. Like `login` it takes a 
list of proofs, but also accepts `label`, `scopes`, and `expires`. Scopes are a way to limit the session to subset of 
endpoints on your server. Expires allows a specific session length, which is capped as the SubjectHandlers 
getSessionExpiration value.

### ProofChecks
The `proof-checks` endpoints takes a list of proofs and returns if the subject is readyToLogin, the strength required to 
login as well as any other available proofs for the subject to use. It is essentially the `login` endpoints without 
the Refresh Token.

### TokenSimple
The `tokenSimple` endpoints takes a Refresh Token and returns a new Access Token

### CreateSubSession
Allows you to create another, more limited session from your current session

### SessionTerminate
Terminates/Revokes the current authenticated Session.

### OtherSessionTerminate
Terminate/Revoke another session for the Subject.

### openSession
This is an Oauth Endpoint

### generateOauthCode
This is an Oauth Endpoint

### token
This is an Oauth Endpoint