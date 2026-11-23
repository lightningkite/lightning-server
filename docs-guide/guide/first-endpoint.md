# Your first endpoint

From an empty server to a tested, typed JSON endpoint.  Assumes the Gradle setup from [Setup](setup.md).

## Imports

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
```

## A plain endpoint

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server -->
```kotlin
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
```

A server is an `object` extending `ServerBuilder`.  `path` is its root; `.get` picks the method, and `bind` attaches
the handler.  Keep each endpoint in a `val` so tests and other code can refer to it.

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#hello-server-test -->
```kotlin
fun helloServerTest() = HelloServer.testBlocking(settings = {}) {
    val response = HelloServer.root.test()
    check(response.body?.text() == "Hello, Lightning Server!")
}
```

`testBlocking` builds an in-memory runtime with default settings and runs the suspending block, blocking the
thread until it finishes, which is what a JUnit `@Test` method wants.  Inside it, `handler.test()` calls the
endpoint directly: no port, no HTTP client.  See [Testing](testing.md).

## Path parameters

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

`.arg<T>("name")` adds a typed segment; its parsed value is `request.path.arg1` (then `arg2`, `arg3`).  In tests,
pass the values to `.test()`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#greet-server-test -->
```kotlin
fun greetServerTest() = GreetServer.testBlocking(settings = {}) {
    val response = GreetServer.greet.test("World")
    check(response.body?.text() == "Hello, World!")
}
```

More path shapes, query parameters, and sub-servers are in [Routing](routing.md).

## A typed endpoint

`ApiHttpHandler` takes and returns `@Serializable` types.  It is what you use for real APIs: it parses and
validates input, serializes output, documents itself in OpenAPI, and appears in generated client SDKs.

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-types -->
```kotlin
@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val echo: String, val length: Int)
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server -->
```kotlin
object EchoServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /echo
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

- `registerBasicMediaTypeCoders()` registers JSON, form, and Kotlin-bytes bodies.  Without it the server can't read
  or write `EchoRequest`.
- `auth = noAuth` makes the endpoint public.  Use `UserAuth.require()` to demand a signed-in user (see
  [Authentication](auth.md)).
- The input and output types come from the lambda's parameter and return type.

A typed endpoint's `.test()` takes the authentication (here `null`) and the input, and returns the output:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server-test -->
```kotlin
fun echoServerTest() = EchoServer.testBlocking(settings = {}) {
    val result = EchoServer.echo.test(null, EchoRequest("ping"))
    check(result.echo == "ping")
    check(result.length == 4)
}
```

## Next

- [Running](running.md): serve `HelloServer` on a port
- [Typed endpoints](typed-endpoints.md): error cases, status codes, examples
- [Database](database.md): store and query models
- [Client SDKs](client-sdks.md): generate a Kotlin or TypeScript client from these endpoints
