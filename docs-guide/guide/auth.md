# Authentication

Every endpoint declares an `AuthRequirement`.  On each request the framework reads the caller's credentials into an
`Authentication<SUBJECT>` and checks it against that requirement before your handler runs.  This chapter covers that
side: defining who can authenticate, requiring it on endpoints, and reading it in handlers.  How users get a token in
the first place is in [Proof-based login](proof-session.md).

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.*
```

The subject is a plain model.  It has no server dependencies, so it can live in a shared module:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#user-model -->
```kotlin
@Queryable
data class UserProfile(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
    val isAdmin: Boolean = false,
) : HasId<Uuid>
```

A `PrincipalType` tells the framework how to serialize the subject and its ID, and how to load it.  It lives in the
server module, next to the table it reads:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#user-auth -->
```kotlin
object UserAuth : PrincipalType<UserProfile, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<UserProfile> = UserProfile.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): UserProfile =
        UserProfileServer.userProfiles().get(id)
            ?: throw NotFoundException("User not found")
}
```

Register it and require it:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-server -->
```kotlin
object UserProfileServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val userProfiles = database.registerTable<UserProfile>("UserProfile")

    init {
        register(UserAuth)
        registerBasicMediaTypeCoders()
    }

    val getProfile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get current user profile",
        auth = UserAuth.require(),
        implementation = { _: Unit -> auth.fetch() }
    )
}
```

- `register(UserAuth)` lets tokens naming this principal be decoded.  `SessionManager` and `AuthEndpoints` register
  their principal for you.
- Inside the handler, `auth` is an `Authentication<UserProfile>`.  `auth.id` is read from the token with no database
  call.  `auth.fetch()` loads the subject and caches it on that `Authentication`, so later calls in the same request
  are free.
- `fetch` should throw `NotFoundException` for an unknown ID rather than return null.
- The principal's `name` (default: the subject's simple class name) is written into tokens and used as
  `IdentificationAndPassword.type`.  Renaming it invalidates outstanding tokens.

## Optional authentication

Combine requirements with `or`.  `noAuth` accepts anyone and is always tried last:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#optional-auth -->
```kotlin
    val greeting = path.path("greeting").get bind ApiHttpHandler(
        summary = "Greet the caller",
        auth = UserAuth.require() or noAuth,
        implementation = { _: Unit ->
            val user = authOrNull?.fetch()
            if (user == null) "Hello, guest" else "Hello, ${user.name}"
        }
    )
```

Use `authOrNull` when the requirement may accept anonymous callers; `auth` is only available when it can't.  `or` also
accepts several principal types: `UserAuth.require() or AdminAuth.require()`.

## Scopes, recency, and custom checks

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#requirement-options -->
```kotlin
    val readReports = UserAuth.require(scope = RequiredScope("reports:read"))
    val recentlySignedIn = UserAuth.require(maxAge = 10.minutes)
    val adminOnly = UserAuth.require { it.fetch().isAdmin }
```

| Parameter | Passes when |
|---|---|
| `scope` / `scopes` | The granted scopes cover every required scope.  The default is `RequiredScope.root`, so a session with narrowed scopes is rejected unless the endpoint names a scope it has. |
| `maxAge` | The user proved their identity no longer ago than this, measured from `Authentication.sessionIssuedAt`.  Refreshing a token, creating a sub-session, and masquerading don't reset it. |
| `requirement` | The suspending check returns true. |

Scopes are `:`-separated paths.  A `GrantedScope` covers every `RequiredScope` it is a prefix of: granted `reports`
meets required `reports:read`, and granted `*` (`GrantedScope.root`, what a normal login gets) meets everything.
Granted `reports:read` does not meet required `reports`.

For requirements that accept any principal type, use `anyAuth` (authenticated, no scope needed), `recentRootAuth`
(root scope, authenticated within 10 minutes), or `AuthRequirement.Authenticated(scopes, maxAge, requirement)`.

A request with no credentials, or whose authentication fails the requirement, gets **403**.  A request with a token
that is malformed, expired, or revoked gets **401**, which tells the client to refresh.

## Super-user, admin, and developer

Framework modules guard their administrative operations (session administration, proof secret tables, funnels,
denormalization refresh) with `AuthRequirement.IsSuperUser`, `IsAdmin`, and `IsDeveloper`.  They reject everyone until
you set them, and `IsAdmin` and `IsDeveloper` fall back to `IsSuperUser`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#super-user -->
```kotlin
        AuthRequirement.isSuperUser = UserAuth.require { it.fetch().isAdmin }
```

Set it in a `ServerBuilder`'s `init`.  `AuthRequirement.isAdmin` and `AuthRequirement.isDeveloper` are set the same
way, and your own endpoints can use `auth = AuthRequirement.IsAdmin`.

## The `Authentication` object

| Member | Meaning |
|---|---|
| `id` | The subject's ID. |
| `fetch()` | The subject, loaded through `PrincipalType.fetch` and cached on this object. |
| `principalName`, `rawId` | The principal's name and the serialized ID. |
| `scopes` | The granted scopes. |
| `sessionId` | The session it came from, or null (test auths, masquerades). |
| `sessionIssuedAt` | When the user last proved their identity for this session.  `maxAge` measures from this. |
| `tokenIssuedAt` | When the access token carrying it was minted, or null if it didn't come from an access token. |
| `expiration` | When it stops being valid, or null. |
| `fromMasquerade` | The real caller's authentication, when masquerading. |

## Caching values on the authentication

An `AuthCacheKey` computes a value from the `Authentication` once and keeps it in `Authentication.cache`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-cache-key -->
```kotlin
object IsAdmin : AuthCacheKey<UserProfile, Boolean> {
    override val id = "user-is-admin"
    override val serializer = Boolean.serializer()
    override val expireAfter = 5.minutes

    context(server: ServerRuntime)
    override suspend fun calculate(input: Authentication<UserProfile>): Boolean =
        input.fetch().isAdmin
}

context(server: ServerRuntime)
suspend fun canSeeAuditLog(auth: Authentication<UserProfile>): Boolean = auth.get(IsAdmin)
```

The cache is serialized into the access token, so a value computed on one request rides along on later ones until the
token or `expireAfter` expires.  Keys listed in `PrincipalType.precache` are computed when the access token is minted.
The subject loaded by `fetch()` is local-only and never enters the token.

!!! warning
    `JwtTokenFormat` tokens are signed but not encrypted, so the client can read anything in the cache.  The default
    `PrivateTinyTokenFormat` encrypts it.

## Masquerading

A caller can act as another subject by sending `X-Masquerade: <principal name>/<id>`, or a query parameter of the same
name for WebSockets.  Every masquerade is refused (403) until the target's principal type allows it:

```kotlin
object UserAuth : PrincipalType<UserProfile, Uuid> {
    // ...
    context(server: ServerRuntime)
    override suspend fun permitMasquerade(from: Authentication<*>, into: Authentication<UserProfile>): Boolean =
        AuthRequirement.IsSuperUser.accepts(from)
}
```

The masqueraded authentication keeps the caller's scopes, expiration, and `sessionIssuedAt`, and records the caller in
`fromMasquerade`.

## Testing

`PrincipalType.testAuth(subject)` builds an `Authentication` without logging in.  Pass it as the first argument of a
typed endpoint's `test`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-test -->
```kotlin
fun authTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val alice = userProfiles().insertOne(UserProfile(name = "Alice", email = "alice@example.com"))

    val profile = getProfile.test(UserAuth.testAuth(alice), Unit)
    check(profile._id == alice._id)
}
```

`testAuth` also takes `issuedAt` (stored as `sessionIssuedAt`; set it in the past to test `maxAge`) and `scopes`
(default root).

Calling the endpoint as a plain `HttpHandler` runs the full HTTP pipeline, credential reading included:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-rejection-test -->
```kotlin
fun authRejectionTest() = UserProfileServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val response = getProfile.test()
    check(response.status.code == 403)
}
```

To test with a real token, log in through the proof endpoints; see
[Proof-based login](proof-session.md#testing-a-login).

## Custom credential readers

`SessionManager` is an `Authentication.Reader`: it turns the access token in the `Authorization` header (or, for a
WebSocket connect, a query parameter) into an `Authentication`.  For other credentials, such as API keys, implement `Authentication.Reader` and register it
with `authReaders.register(reader)` in a `ServerBuilder`.  Readers run highest `priority` first and the first non-null
result wins.  Return null for "no credentials of my kind", and throw `UnauthorizedException` for "credentials, but
invalid".

## See also

- [Proof-based login](proof-session.md): sessions, proof methods, tokens, and revocation
- [OAuth](oauth.md): signing in with Google, Apple, Microsoft, or GitHub
- [OAuth authorization server](oauth-provider.md): issuing tokens to MCP clients and other apps
- [Permissions](permissions.md): per-row access rules built on `auth`
- [Testing](testing.md)
