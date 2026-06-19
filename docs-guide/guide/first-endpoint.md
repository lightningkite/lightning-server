# Your First Endpoint

Lightning Server is a Kotlin server framework that lets you define endpoints,
route handlers, and typed APIs with minimal boilerplate.  This chapter walks
you from an empty server definition to a tested, typed API endpoint.

> **How these examples work.**  Every code block in this chapter is a named
> region from a compiled, tested Kotlin source file.  The drift-check test
> (`./gradlew :docs-guide:test`) asserts the Markdown is byte-identical to
> the source, so the examples can never silently break.

## Imports

All examples in this chapter use the following imports.  Copy them to the top
of your file:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
```

A few non-obvious paths to note:

- `ServerBuilder` is in `definition.builder`, not the root package.
- `.get` / `.post` on a path are extension properties from `http` — import
  them explicitly since the `http.*` wildcard does not always pull them in.
- `HttpResponse.plainText` is a top-level extension imported from
  `com.lightningkite.lightningserver.plainText`, not from `http`.
- `arg1` (and `arg2`, `arg3`) are extension properties on the path object,
  imported from `pathing`.
- The `SERVER.test {}` block comes from `runtime.test`; the typed
  `ApiHttpHandler.test()` overload comes from `typed`.
- `bind` is a member of `ServerBuilder` — it is available inside the object
  body without a separate import.

## The ServerBuilder

Everything in Lightning Server starts with a `ServerBuilder`.  You extend it
as an `object` — not a class — because every endpoint is a singleton that the
framework discovers at build time.

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server -->
```kotlin
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
```

`path` is a special property provided by `ServerBuilder` that represents the
root URL of this object.  Calling `.get` on a path produces a route spec;
`bind` (a `ServerBuilder` member, no import needed) attaches your handler to
it.

To exercise this endpoint call `SERVER.test {}`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server-test -->
```kotlin
fun helloServerTest() = runBlocking {
    HelloServer.test(settings = {}) {
        val response = HelloServer.root.test()
        check(response.body?.text() == "Hello, Lightning Server!")
    }
}
```

`test {}` spins up an ephemeral in-memory runtime, initialises all settings
with their defaults, and then runs your block.  Inside the block every
`HttpHandler` gains a `.test()` extension that fires a request and returns the
response — no network, no server process.

Note the `runBlocking {}` wrapper: the `test {}` action lambda is not a
suspend function, but the `.test()` calls on handlers are suspend, so they
must run inside a coroutine.  `runBlocking` provides that coroutine without
changing how the code reads in a test method.

## Path Parameters

Add dynamic segments with `.arg<T>("name")`.  The parsed value lands in
`request.path.arg1` (and `.arg2`, `.arg3` for subsequent arguments):

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#greet-server -->
```kotlin
object GreetServer : ServerBuilder() {

    // GET /greet/{name}
    val greet = path.path("greet").arg<String>("name").get bind HttpHandler { request ->
        val name = request.path.arg1
        HttpResponse.plainText("Hello, $name!")
    }
}
```

Pass the path argument value to `.test()`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#greet-server-test -->
```kotlin
fun greetServerTest() = runBlocking {
    GreetServer.test(settings = {}) {
        val response = GreetServer.greet.test("World")
        check(response.body?.text() == "Hello, World!")
    }
}
```

The framework handles URL encoding and decoding automatically; the string
`"World"` is inserted into the path without escaping.

## Typed Endpoints

`ApiHttpHandler` adds automatic JSON serialisation, input validation, OpenAPI
documentation, and SDK generation on top of any endpoint.  It is the
recommended choice for production APIs.

Declare your input and output as plain `@Serializable` data classes:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-types -->
```kotlin
@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val echo: String, val length: Int)
```

Then bind the endpoint with `ApiHttpHandler`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server -->
```kotlin
object EchoServer : ServerBuilder() {

    // POST /echo — accepts typed JSON, returns typed JSON
    val echo = path.path("echo").post bind ApiHttpHandler(
        summary = "Echo a message",
        description = "Returns the message back with its character count.",
        auth = noAuth,
        implementation = { input: EchoRequest ->
            EchoResponse(
                echo = input.message,
                length = input.message.length
            )
        }
    )
}
```

`ApiHttpHandler` infers `INPUT` and `OUTPUT` from the lambda's type annotation
and resolves their serialisers automatically.  `noAuth` marks the endpoint as
publicly accessible; the `implementation` lambda receives the deserialised
input and must return the output — serialisation and HTTP status are handled
for you.  The lambda body has access to a `ServerRuntime` context for calling
services (database, cache, etc.); neither is used in this minimal example.

In tests, `ApiHttpHandler.test()` accepts the input object directly and
returns the typed output — no JSON round-trip or `body.contains()` needed:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server-test -->
```kotlin
fun echoServerTest() = runBlocking {
    EchoServer.test(settings = {}) {
        // ApiHttpHandler.test() accepts null auth for noAuth endpoints and
        // returns the typed output directly — no JSON manipulation needed.
        val result = EchoServer.echo.test(null, EchoRequest("ping"))
        check(result.echo == "ping")
        check(result.length == 4)
    }
}
```

The first argument to `.test()` is `null` because `noAuth` means there is no
session object.  For authenticated endpoints you pass an
`Authentication<YourUser>` value instead.

## What's Next

- **Authenticated endpoints** — swap `noAuth` for `authOptions<YourUser>()`
  to require a valid session token.
- **Database access** — define a `setting("database", Database.Settings())`
  and query it with the type-safe condition/modification DSL inside any handler.
- **Error cases** — document expected failures with the `errorCases` parameter
  on `ApiHttpHandler`; the framework maps them to HTTP status codes and
  auto-generates client-side exception types.
- **SDK generation** — run `./gradlew :demo:run --args="sdk"` to emit a
  TypeScript or Kotlin client from your typed endpoint definitions.
