<!--- TEST_NAME FirstEndpointTest -->

# Your First Endpoint

Lightning Server is a Kotlin server framework that lets you define endpoints,
route handlers, and typed APIs with minimal boilerplate. This chapter walks you
from an empty server definition to a tested, typed API endpoint.

## The ServerBuilder

Everything in Lightning Server starts with a `ServerBuilder`. You extend it as
an `object` — not a class — because every endpoint must be a singleton that
the framework can discover at build time.

The example below defines a minimal server with a single `GET /` endpoint and
calls it from a `main` function using the in-memory test runner:

```kotlin
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking

object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}

fun main() = runBlocking {
    HelloServer.test({}) {
        val response = HelloServer.root.test()
        println(response.body?.text())
    }
}
```

<!--- KNIT example-first-endpoint-01.kt -->

The output is:

```text
Hello, Lightning Server!
```

<!--- TEST lines.last() == "Hello, Lightning Server!" -->

`path` is a special property provided by `ServerBuilder` representing the
root URL of this object. Calling `.get` on a path produces a route spec; `bind`
attaches your handler to it.

`Server.test({}) { ... }` creates an ephemeral in-memory runtime, initialises
all settings with their defaults, and runs your block with the test runner in
scope. Inside the block every handler gains a `.test()` extension that fires a
request and returns the response — no network involved.

## Path Parameters

Add dynamic segments to a path with `.arg<T>("name")`. The parsed value lands
in `request.path.arg1` (and `.arg2`, `.arg3` for subsequent arguments):

```kotlin
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking

object GreetServer : ServerBuilder() {

    // GET /greet/{name}
    val greet = path.path("greet").arg<String>("name").get bind HttpHandler { request ->
        val name = request.path.arg1
        HttpResponse.plainText("Hello, $name!")
    }
}

fun main() = runBlocking {
    GreetServer.test({}) {
        val response = GreetServer.greet.test("World")
        println(response.body?.text())
    }
}
```

<!--- KNIT example-first-endpoint-02.kt -->

```text
Hello, World!
```

<!--- TEST -->

The string passed to `.test("World")` maps to the first path argument. The
framework handles URL encoding/decoding automatically.

## Typed Endpoints

The `ApiHttpHandler` wrapper adds automatic JSON serialization, input
validation, OpenAPI documentation, and SDK generation on top of any endpoint.
It is the recommended choice for production APIs.

```kotlin
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData

@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val echo: String, val length: Int)

object EchoServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /echo — accepts JSON, returns JSON
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

fun main() = runBlocking {
    EchoServer.test({}) {
        // Send a JSON body and get back the JSON response through the full HTTP pipeline
        val response = EchoServer.echo.test(
            body = TypedData.text("""{"message":"ping"}""", MediaType.Application.Json)
        )
        // The response body is JSON; print just the echo field from it
        val body = response.body?.text() ?: ""
        println(body.contains("\"echo\":\"ping\""))
        println(body.contains("\"length\":4"))
    }
}
```

<!--- KNIT example-first-endpoint-03.kt -->

```text
true
true
```

<!--- TEST -->

`ApiHttpHandler` infers `INPUT` and `OUTPUT` from your lambda's type annotation,
resolves their serializers automatically, and handles JSON content-negotiation.
`noAuth` marks the endpoint as publicly accessible without a session.

## What's Next

- **Authenticated endpoints** — swap `noAuth` for `authOptions<YourUser>()` to
  require a valid session token.
- **Database access** — define a `setting("database", Database.Settings())`
  and query it with the type-safe condition/modification DSL inside any handler.
- **Error cases** — document expected failures with the `errorCases` parameter
  on `ApiHttpHandler`; the framework maps them to HTTP status codes and
  auto-generates client-side exception types.
