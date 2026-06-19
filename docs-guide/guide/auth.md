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

## Imports

All examples in this chapter use the following imports:

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
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
```

Non-obvious import locations:

- `com.lightningkite.lightningserver.auth.*` brings in `PrincipalType`,
  `AuthRequirement`, `noAuth`, `testAuth`, `fetch`, `id`, and the `register`
  extension on `ServerBuilder`.
- `com.lightningkite.lightningserver.typed.*` also brings in the `auth` property
  extension on `HttpAccess` — needed to read `auth` inside an
  `ApiHttpHandler` implementation lambda.
- `com.lightningkite.lightningserver.serialization.*` registers JSON (and other standard) media type encoders/decoders.  Required
  when testing through the full HTTP pipeline (`HttpHandler.test()`), including
  error response serialization.
- `kotlinx.serialization.builtins.serializer` is imported (not
  `kotlinx.serialization.serializer`) because `@Serializable` classes generate
  a companion-scoped `serializer()` that conflicts with the top-level
  `serializer<T>()` reified function from the `kotlinx-serialization-core`
  artifact.

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
        // registerBasicMediaTypeCoders() enables JSON serialization of HTTP request/response bodies,
        // including error responses. Required when testing via HttpHandler.test() (the full HTTP pipeline).
        registerBasicMediaTypeCoders()
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

Note that `fetch()` must call the service owned by whichever `ServerBuilder`
declares the `database` setting — in this chapter that is
`UserProfileServer.database()`.

## Testing an Authenticated Endpoint

`PrincipalType.testAuth(subject)` creates a synthetic `Authentication<SUBJECT>`
for testing.  It must be called inside a `test {}` block because it needs a
`ServerRuntime` in context (to capture the current clock time as `issuedAt`).

Pass the resulting auth token as the first argument to the typed `.test()` call.

> To wrap these examples in a test class, annotate your test methods with `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` + `runBlocking` pattern.

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

## Testing: the Rejection Path

When a protected endpoint is called without any credentials, the framework's
`assert()` function throws `ForbiddenException` (HTTP 403).  Note that 403
("you're not allowed") rather than 401 ("who are you?") is used here because
no auth token was presented at all.  The `HttpHandler.test()` extension exercises
the full HTTP pipeline including the exception handler, so the exception is
serialized to an `HttpResponse` — inspect `.status.code` on the returned value:

<!-- sample: com/lightningkite/lightningserver/guide/samples/AuthSamples.kt#auth-rejection-test -->
```kotlin
fun authRejectionTest() = runBlocking {
    UserProfileServer.test(settings = { database set Database.Settings("ram") }) {
        // Drive the endpoint as an HttpHandler (not ApiHttpHandler.test()) so the full
        // auth-checking pipeline runs. The framework serializes the rejection into an HTTP
        // response; inspect .status.code on the returned HttpResponse.
        // Note: the framework throws ForbiddenException (403) when no credentials are
        // supplied, which is distinct from an invalid token (401).
        val response = UserProfileServer.getProfile.test()
        check(response.status.code == 403)
    }
}
```

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
