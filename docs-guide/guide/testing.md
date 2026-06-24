# Testing Your Server

Lightning Server was designed with testing in mind.  A single in-memory
runtime — no running server, no network, no external services — exercises your
endpoints exactly as production code does.  This chapter is the canonical
reference for all the testing patterns used throughout the guide.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.*
```

Notes:

- `kotlinx.serialization.builtins.serializer` must stay explicit — the wildcard
  `kotlinx.serialization.*` brings in a top-level reified `serializer()` that
  conflicts with the companion-generated `serializer()` inside `@Serializable`
  classes when you call e.g. `Uuid.serializer()` directly.
- `com.lightningkite.lightningserver.auth.*` brings in `PrincipalType`,
  `noAuth`, `require`, `testAuth`, `fetch`, and the `register` extension on
  `ServerBuilder`.

## The Example Server

To keep this chapter self-contained, it defines its own small server with
three endpoints — a plain handler, a `noAuth` typed endpoint, and an
authenticated typed endpoint — and its own `Member` principal type.

### Data types and the Member principal type

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-server-types -->
```kotlin
@Serializable
data class GreetRequest(val name: String)

@Serializable
data class GreetResponse(val greeting: String)

@Serializable
data class Member(
    override val _id: Uuid = Uuid.random(),
    val name: String,
) : HasId<Uuid> {
    companion object : PrincipalType<Member, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<Member> = Member.serializer()

        // In-memory store for testing — fetch() looks up by ID here.
        // A production implementation would query a database table instead.
        val store = mutableMapOf<Uuid, Member>()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): Member =
            store[id] ?: throw com.lightningkite.lightningserver.NotFoundException("Member not found")
    }
}
```

`Member.Companion` implements `PrincipalType<Member, Uuid>`.  The three
things it must supply: an `idSerializer`, a `subjectSerializer`, and a
`fetch(id)` function.  Here `fetch()` reads from a companion-level map —
simple for testing, no external service needed.  A real server would query a
database table instead.

### The server

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-server -->
```kotlin
object TestingServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    init {
        // register() is an extension on ServerBuilder from the auth module.
        // It makes this PrincipalType discoverable when deserializing auth tokens.
        register(Member)
    }

    // Plain HttpHandler — no type safety, returns an HttpResponse directly
    val hello = path.path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }

    // noAuth typed endpoint — auth = noAuth means no authentication is required.
    // The first argument to ApiHttpHandler.test() is the auth token; pass null for noAuth.
    val greet = path.path("greet").post bind ApiHttpHandler(
        summary = "Greet someone",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = listOf(
            LSError(http = 400, detail = "empty-name", message = "Name must not be blank")
        ),
        implementation = { input: GreetRequest ->
            if (input.name.isBlank())
                throw BadRequestException(detail = "empty-name", message = "Name must not be blank")
            GreetResponse(greeting = "Hello, ${input.name}!")
        }
    )

    // Authenticated typed endpoint — auth = Member.require() means a Member token is required.
    // The first argument to ApiHttpHandler.test() must be a non-null Authentication<Member>.
    val profile = path.path("profile").get bind ApiHttpHandler(
        summary = "Get member profile",
        auth = Member.require(),
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        implementation = { _: Unit ->
            // auth is Authentication<Member>; fetch() loads the full Member from the store
            auth.fetch()
        }
    )
}
```

## Why `runBlocking` Is Always Required

`SERVER.test {}` is an `inline` function.  Its `action` lambda is **not**
`suspend` — plain lambdas cannot suspend.  But because the lambda is inlined,
`suspend` calls inside it are lifted into the surrounding coroutine scope.
There is no surrounding scope unless you provide one, so every call to
`SERVER.test {}` must be wrapped in `runBlocking {}`.

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-runblocking -->
```kotlin
// The test {} action lambda is NOT suspend, even though all .test() calls inside it are.
// This is because test {} is an inline function, and the action is a plain lambda — the
// suspend calls work only because they are inlined into the surrounding runBlocking scope.
// Every call to SERVER.test { } must therefore be wrapped in runBlocking { }.
fun runBlockingExplanation() = runBlocking {
    TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
        // suspend calls like .test() work here because this block is inlined into runBlocking
        val response = TestingServer.hello.test()
        check(response.status == HttpStatus.OK)
    }
}
```

> **Note:** Making `action` a `suspend` lambda would remove this
> boilerplate — every example and test would become cleaner.  A future PR
> could make this change since `test {}` is `inline` and the Kotlin compiler
> handles inline + suspend correctly.  For now, `runBlocking {}` is the
> required wrapper.

## Testing a Plain `HttpHandler`

`HttpHandler.test()` returns an `HttpResponse`.  Inspect `.status` and
`.body?.text()` to assert the outcome.  Note that `.text()` is a synchronous
call on `TypedData`, not a suspend function.

The `settings` lambda configures each `ServerSetting` before the runtime
starts.  Use `"ram"` as the URL for `Cache.Settings` or `Database.Settings`
to get the built-in in-process implementation — no external services needed.
The `set` infix function is a context extension on `ServerSettings` (brought
in by `import com.lightningkite.lightningserver.settings.set`).

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-plain-handler -->
```kotlin
fun plainHandlerTest() = runBlocking {
    // The settings lambda configures each ServerSetting before the runtime starts.
    // "ram" is the built-in URL for the in-process cache — no external dependencies.
    TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
        // HttpHandler.test() returns an HttpResponse.
        // Inspect .status and .body?.text() to assert the outcome.
        val response = TestingServer.hello.test()
        check(response.status == HttpStatus.OK)
        check(response.body?.text() == "Hello!")
    }
}
```

## Testing a Typed `ApiHttpHandler` — noAuth

`ApiHttpHandler.test(auth, input)` returns the typed `OUTPUT` directly.
There is no `HttpResponse` to unwrap and no JSON to parse.

The first argument is the authentication token.  For `noAuth` endpoints the
`USER` type parameter is `Nothing?`, so pass `null`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-noauth-typed -->
```kotlin
fun noAuthTypedTest() = runBlocking {
    TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
        // ApiHttpHandler.test() takes (auth, input) and returns the typed OUTPUT directly —
        // no HttpResponse to unwrap, no JSON to parse.
        //
        // For noAuth endpoints (USER = Nothing?), the auth argument must be null.
        val result = TestingServer.greet.test(null, GreetRequest("Alice"))
        check(result.greeting == "Hello, Alice!")
    }
}
```

## Testing a Typed `ApiHttpHandler` — Authenticated

For endpoints that require a specific principal type (`auth = Member.require()`),
pass a non-null `Authentication<Member>`.

`PrincipalType.testAuth(subject)` creates a synthetic authentication token for
testing.  It must be called **inside** a `test {}` block because it needs a
`ServerRuntime` in context (to capture the current clock time as `issuedAt`).

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-auth-typed -->
```kotlin
fun authTypedTest() = runBlocking {
    TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
        val alice = Member(name = "Alice")
        Member.store[alice._id] = alice  // seed the in-memory store so fetch() finds her

        // testAuth() creates a synthetic Authentication<Member> for the test.
        // It must be called inside a test {} block because it needs a ServerRuntime in context.
        val aliceAuth = Member.testAuth(alice)

        // For authenticated endpoints (USER is non-nullable), pass a non-null Authentication.
        val result = TestingServer.profile.test(aliceAuth, Unit)
        check(result.name == "Alice")

        Member.store.clear()  // clean up so tests don't bleed state
    }
}
```

## Asserting an Error Path

`ApiHttpHandler.test()` calls the handler directly — it does **not** go
through the HTTP serialization layer.  Errors thrown inside the implementation
are propagated as Kotlin exceptions.

Catch `HttpStatusException` and check `.status.code` and `.detail` to verify
the right error fired:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-error-path -->
```kotlin
fun errorPathTest() = runBlocking {
    TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
        // ApiHttpHandler.test() propagates HttpStatusException directly as a Kotlin exception.
        // It does NOT serialize to/from HTTP, so the exception is exactly what the handler threw.
        // Catch HttpStatusException and inspect .status.code and .detail to verify the right error fired.
        try {
            TestingServer.greet.test(null, GreetRequest(""))
            error("Expected BadRequestException to be thrown")
        } catch (e: HttpStatusException) {
            check(e.status.code == 400)
            check(e.detail == "empty-name")
        }
    }
}
```

> **Note:** `ApiHttpHandler.test()` bypasses the HTTP exception-to-response
> serialization path.  The raw exception is more convenient for assertions
> (`.status.code`, `.detail`), but it means the JSON serialization of errors
> is not exercised.  To test that path, drive the `HttpHandler` layer with
> `HttpHandler.test()` and inspect `response.status.code`.

## A Complete Test Class

A test function must be annotated with `@Test` (`kotlin.test.Test`) for the
test runner to discover and execute it.  Functions without `@Test` compile and
can be called manually, but the runner silently ignores them.

Here is a complete, copy-pasteable test class that wires all the patterns
together:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TestingSamples.kt#testing-full-example -->
```kotlin
// A complete, copy-pasteable test class.
// @Test marks each method for the test runner. runBlocking {} is required because
// test {} is inline (not suspend), so the outer coroutine scope must be provided explicitly.
class GreetServerTest {
    @Test
    fun `greet returns greeting for valid name`() = runBlocking {
        TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
            val result = TestingServer.greet.test(null, GreetRequest("Alice"))
            check(result.greeting == "Hello, Alice!")
        }
    }

    @Test
    fun `greet rejects blank name`() = runBlocking {
        TestingServer.test(settings = { cache set Cache.Settings("ram") }) {
            try {
                TestingServer.greet.test(null, GreetRequest(""))
                error("Expected exception")
            } catch (e: HttpStatusException) {
                check(e.status.code == 400)
                check(e.detail == "empty-name")
            }
        }
    }
}
```

Key points:
- `@Test` is `kotlin.test.Test` — import `kotlin.test.Test`.
- Each `@Test` method wraps its `SERVER.test {}` call in `runBlocking {}`.
- Methods are inside a class (standard JUnit requirement).
- The `settings` lambda resets state per test — each test gets a fresh `"ram"` cache.

## Quick Reference

| What you're testing | Method | Returns | Auth argument |
|---|---|---|---|
| Plain `HttpHandler` | `handler.test()` | `HttpResponse` | — |
| Typed `noAuth` endpoint | `handler.test(null, input)` | typed `OUTPUT` | `null` |
| Typed authenticated endpoint | `handler.test(auth, input)` | typed `OUTPUT` | `Member.testAuth(member)` |

### Import cheatsheet

| Symbol | Import |
|---|---|
| `@Test` annotation | `kotlin.test.Test` |
| `SERVER.test {}` block | `com.lightningkite.lightningserver.runtime.test.test` |
| `ApiHttpHandler.test()` | `com.lightningkite.lightningserver.typed.test` |
| `HttpHandler.test()` | same as `runtime.test.test` (included) |
| `auth` (inside handler lambda) | `com.lightningkite.lightningserver.typed.auth` |
| `testAuth()` | `com.lightningkite.lightningserver.auth.*` |
| `fetch()` / `id` on `Authentication` | `com.lightningkite.lightningserver.auth.*` |
| `set` (settings lambda) | `com.lightningkite.lightningserver.settings.set` |
| `HttpResponse.plainText()` | `com.lightningkite.lightningserver.plainText` |
