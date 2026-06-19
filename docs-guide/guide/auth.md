# Authentication & Sessions

Lightning Server's auth model has three layers:

- **Proof** — evidence that you are who you say (email OTP, SMS PIN, password,
  OAuth token).  The `sessions-email`, `sessions-sms`, and related modules
  supply ready-made proof endpoints.
- **Principal / Subject** — the data model that represents an authenticated
  entity (e.g. a `User` record).  You define this; the framework provides the
  wiring.
- **`Authentication<SUBJECT>`** — an in-memory token carrying the subject's ID,
  the time it was issued, and which scopes it carries.  Endpoints receive this
  token and use it to look up the full subject on demand.

This chapter covers the practical core: defining a principal type, requiring
auth on an endpoint, reading the authenticated user inside a handler, and
testing authenticated endpoints.

> **How these examples work.**  Every code block is a named region from a
> compiled, tested Kotlin source file.  `./gradlew :docs-guide:test` asserts
> byte-equality between what you read here and the running source, so the
> examples can never silently break.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-imports -->
```kotlin
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.auth
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid
```

Non-obvious import locations:

- `com.lightningkite.lightningserver.auth.*` brings in `PrincipalType`,
  `AuthRequirement`, `noAuth`, `testAuth`, `fetch`, `id`, and the `register`
  extension on `ServerBuilder`.
- `com.lightningkite.lightningserver.typed.auth` is the `auth` property
  extension on `HttpAccess` — needed to read `auth` inside an
  `ApiHttpHandler` implementation lambda.
- `kotlinx.serialization.builtins.serializer` is imported so that the
  companion-generated `serializer()` function is in scope for
  `subjectSerializer`.

## Defining a Principal Type

A **principal type** is a data class that implements `HasId` and whose
`companion object` implements `PrincipalType<SUBJECT, ID>`.  The companion
tells the framework three things:

1. How to serialize the ID (`idSerializer`)
2. How to serialize the full subject (`subjectSerializer`)
3. How to fetch a subject by ID from durable storage (`fetch`)

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#user-model -->
```kotlin
@Serializable
@GenerateDataClassPaths
data class UserProfile(
    override val _id: Uuid = Uuid.random(),
    val name: String,
    val email: String,
) : HasId<Uuid> {
    // The companion implements PrincipalType so this type can be used as an auth subject.
    // It tells the framework how to serialize IDs and how to load a subject from storage.
    companion object : PrincipalType<UserProfile, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<UserProfile> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): UserProfile =
            UserProfileServer.database().table<UserProfile>().get(id)
                ?: throw NotFoundException("User not found")
    }
}
```

`fetch` is a context extension on `ServerRuntime` — it has access to all
registered services via the runtime.  It should throw `NotFoundException` (not
return `null`) when the ID does not exist, so callers receive a clean 404 rather
than a NullPointerException.

`@GenerateDataClassPaths` is optional on principals but is included here
because `fetch` accesses the database table directly, and the query DSL
requires it for condition/modification lambdas (see the previous chapter).

## Registering the Principal Type and Requiring Auth

Call `register(UserProfile)` in your `ServerBuilder.init {}` block.
Registration makes the framework aware of this principal so that
`Authentication<UserProfile>` tokens can be deserialized back to the correct
type when the server restarts.

Use `UserProfile.require()` as the `auth` argument to `ApiHttpHandler` to
declare that the endpoint requires a `UserProfile` token.  Compare this to
`noAuth` (`AuthRequirement.None`) used in earlier chapters for public endpoints.

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-server -->
```kotlin
object UserProfileServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    init {
        // register() makes this principal type discoverable when deserializing tokens.
        register(UserProfile)
    }

    // GET /profile — requires a UserProfile authentication token
    val getProfile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get current user profile",
        // UserProfile.require() returns an AuthRequirement that accepts only tokens issued for UserProfile.
        // Compare to noAuth (AuthRequirement.None) used in earlier chapters.
        auth = UserProfile.require(),
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            // Inside an authenticated handler, `auth` is Authentication<UserProfile>.
            // auth.id gives the Uuid; auth.fetch() loads the full UserProfile from the database.
            auth.fetch()
        }
    )
}
```

Inside the implementation lambda:

- The lambda receiver is `HttpAccess<PATH, UserProfile>`.
- `auth` is `Authentication<UserProfile>` — the in-memory token.
- `auth.id` gives the `Uuid` without a database round-trip (it is stored in
  the token).
- `auth.fetch()` suspends and loads the full `UserProfile` from the database;
  the result is cached on the `Authentication` object so repeated calls within
  one request are free.

Both `auth.id` and `auth.fetch()` are context extensions on `ServerRuntime`,
which is already in scope inside the implementation lambda.

## Testing an Authenticated Endpoint

`PrincipalType.testAuth(subject)` creates a synthetic `Authentication<SUBJECT>`
for testing.  It must be called inside a `test {}` block because it needs a
`ServerRuntime` in context (to capture the current clock time as `issuedAt`).

Pass the resulting auth token as the first argument to the typed `.test()` call:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-test -->
```kotlin
fun authTest() = runBlocking {
    UserProfileServer.test(settings = { database set Database.Settings("ram") }) {
        // Seed a user directly into the database
        val alice = UserProfileServer.database().table<UserProfile>()
            .insertOne(UserProfile(name = "Alice", email = "alice@example.com"))!!

        // testAuth() creates an Authentication<UserProfile> for use in tests.
        // It must be called inside a test {} block because it needs a ServerRuntime in context.
        val aliceAuth = UserProfile.testAuth(alice)

        // Pass the auth token as the first argument to the typed .test() call.
        val profile = UserProfileServer.getProfile.test(aliceAuth, Unit)
        check(profile.name == "Alice")
        check(profile.email == "alice@example.com")
        check(profile._id == alice._id)
    }
}
```

The `test {}` block (from `com.lightningkite.lightningserver.runtime.test.test`)
provides a live `ServerRuntime` with all settings resolved.  The `settings`
lambda configures each setting before the runtime starts — here, `database set
Database.Settings("ram")` switches to the in-process RAM database so no
external infrastructure is needed.

Note: `settings` is a context extension on `ServerRuntime` (same as in the
Services chapter).

## The Proof/Session Flow (conceptual)

The examples above show how to *consume* an `Authentication` token in an
endpoint.  Establishing that token in the first place — logging a user in —
requires a separate proof-and-session flow:

1. **Collect a proof** — the user submits evidence (e.g. an email PIN entered
   via the `sessions-email` endpoints).  The server validates it and returns a
   signed `Proof` object.
2. **Exchange proof for a session** — the client posts the `Proof` to an auth
   endpoint that verifies it meets the required strength, creates a
   server-side session record, and returns a signed bearer token.
3. **Use the bearer token** — subsequent requests carry the token in the
   `Authorization: Bearer ...` header.  The framework's `Authentication.Reader`
   verifies and deserializes it into an `Authentication<SUBJECT>` which is then
   available in the handler via `auth`.

The `sessions`, `sessions-email`, `sessions-sms`, and related modules provide
ready-made proof endpoints; `AuthEndpoints` provides the session exchange
endpoint.  The demo server (`demo/src/main/kotlin/.../Server.kt`) shows how to
wire these together.

> These steps involve substantial module setup and are not reproduced as
> compiled samples here — the above description is illustrative.  See the demo
> server for a complete working example.
