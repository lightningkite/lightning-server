# Your First Endpoint

Lightning Server is a Kotlin server framework that lets you define endpoints,
route handlers, and typed APIs with minimal boilerplate.  This chapter walks
you from an empty server definition to a tested, typed API endpoint.

> **How these examples work.**  Every code block in this chapter is
> automatically verified by the compiled-samples test suite
> (`./gradlew :docs-guide:test`).  The canonical source lives in
> [`docs-guide/src/samples/kotlin/`](../src/samples/kotlin/) — the Markdown
> embeds a copy that CI checks for byte-equality, so the docs can never drift
> from working code.

## The ServerBuilder

Everything in Lightning Server starts with a `ServerBuilder`.  You extend it as
an `object` — not a class — because every endpoint is a singleton that the
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

`path` is a special property provided by `ServerBuilder` representing the
root URL of this object.  Calling `.get` on a path produces a route spec;
`bind` attaches your handler to it.

To exercise this in a test (or a `main` function) use the `test {}` block:

```kotlin
HelloServer.test(settings = {}) {
    val response = HelloServer.root.test()
    assertEquals("Hello, Lightning Server!", response.body?.text())
}
```

`test {}` spins up an ephemeral in-memory runtime, initialises all settings
with their defaults, and runs your block.  Inside the block every
`HttpHandler` gains a `.test()` extension that fires a request and returns
the response — no network, no server process.

## Path Parameters

Add dynamic segments to a path with `.arg<T>("name")`.  The parsed value
lands in `request.path.arg1` (and `.arg2`, `.arg3` for subsequent arguments):

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

The string passed to `.test("World")` maps to the first path argument.
The framework handles URL encoding and decoding automatically.

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

Then bind a handler with `ApiHttpHandler`:

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

In tests, `ApiHttpHandler.test()` accepts the input object directly and
returns the typed output — no JSON serialisation or `body.contains(...)` checks
needed:

```kotlin
EchoServer.test(settings = {}) {
    val result = EchoServer.echo.test(null, EchoRequest("ping"))
    assertEquals("ping", result.echo)
    assertEquals(4, result.length)
}
```

The first argument is `null` here because `noAuth` marks the endpoint as
publicly accessible — there is no session object to pass.

`ApiHttpHandler` infers `INPUT` and `OUTPUT` from your lambda's type
annotation, resolves their serialisers automatically, and handles JSON
content-negotiation.

## What's Next

- **Authenticated endpoints** — swap `noAuth` for `authOptions<YourUser>()`
  to require a valid session token; the `.test()` method then accepts an
  `Authentication<YourUser>` object.
- **Database access** — define a `setting("database", Database.Settings())`
  and query it with the type-safe condition/modification DSL inside any handler.
- **Error cases** — document expected failures with the `errorCases` parameter
  on `ApiHttpHandler`; the framework maps them to HTTP status codes and
  auto-generates client-side exception types.
- **SDK generation** — run `./gradlew :demo:run --args="sdk"` to emit a
  TypeScript or Kotlin client from your typed endpoint definitions.
