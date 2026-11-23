# Routing

Every endpoint is a path, an HTTP method, and a handler, declared as a property on a `ServerBuilder`.  This chapter
covers raw `HttpHandler`s: paths, path arguments, reading requests, and building responses.  For JSON APIs, use
[typed endpoints](typed-endpoints.md), which build on the same paths.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-minimal -->
```kotlin
object RoutingServer : ServerBuilder() {
    // GET /hello
    val hello = path.path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }

    // GET /users/{id}
    val user = path.path("users").arg<Int>("id").get bind HttpHandler { request ->
        HttpResponse.plainText("User ${request.path.arg1}")
    }
}
```

Keep each endpoint in a `val`: tests and other endpoints refer to it by that reference.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-minimal-test -->
```kotlin
fun routingMinimalTest() = RoutingServer.testBlocking(settings = {}) {
    check(RoutingServer.hello.test().body?.text() == "Hello!")
    check(RoutingServer.user.test(42).body?.text() == "User 42")
}
```

All examples in this chapter use these imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.data.*
import kotlinx.html.*
import kotlin.time.Duration.Companion.minutes
```

## Paths

- `path` is the builder's root.  `.path("segment")` adds one literal segment; chain it for more.
- `.arg<T>("name")` adds a typed wildcard segment.  The value is decoded with `T`'s serializer, so `Int`, `Uuid`,
  enums, and value classes all work.  Read it in the handler as `request.path.arg1`, `arg2`, `arg3`.
- `.any` matches any number of trailing segments; read them with `request.path.trailingSegments`.
- `.slash` adds a trailing slash (`/docs/` rather than `/docs`).

Literal segments win over wildcards, so `/users/me` and `/users/{id}` can coexist.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-multi-arg -->
```kotlin
object RoutingPostServer : ServerBuilder() {
    // GET /users/{userId}/posts/{postId}
    val getPost = path.path("users").arg<String>("userId")
        .path("posts").arg<Int>("postId").get bind HttpHandler { request ->
            HttpResponse.plainText("User ${request.path.arg1}, post ${request.path.arg2}")
        }
}
```

Pass path arguments to `.test()` positionally, in order:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-multi-arg-test -->
```kotlin
fun routingMultiArgTest() = RoutingPostServer.testBlocking(settings = {}) {
    check(RoutingPostServer.getPost.test("alice", 7).body?.text() == "User alice, post 7")
}
```

### Trailing segments

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-trailing -->
```kotlin
object RoutingFilesServer : ServerBuilder() {
    // GET /files/{...} matches /files/a, /files/a/b/c, ...
    val file = path.path("files").any.get bind HttpHandler { request ->
        HttpResponse.plainText("File ${request.path.trailingSegments}")
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-trailing-test -->
```kotlin
fun routingTrailingTest() = RoutingFilesServer.testBlocking(settings = {}) {
    val response = RoutingFilesServer.file.test(trailingWildcard = PathSegments(listOf("docs", "readme.txt")))
    check(response.body?.text() == "File docs/readme.txt")
}
```

## HTTP methods

`.get`, `.post`, `.put`, `.patch`, `.delete`, `.options`, and `.head` turn a path into an `HttpEndpoint`.  A path is an
ordinary value, so you can store it once and bind several methods to it:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-methods -->
```kotlin
object RoutingItemServer : ServerBuilder() {
    val items = path.path("items").arg<String>("id")

    val replace = items.put bind HttpHandler { request ->
        HttpResponse.plainText("Replaced ${request.path.arg1}")
    }
    val update = items.patch bind HttpHandler { request ->
        HttpResponse.plainText("Updated ${request.path.arg1}")
    }
    val remove = items.delete bind HttpHandler { request ->
        HttpResponse.plainText("Deleted ${request.path.arg1}")
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-methods-test -->
```kotlin
fun routingMethodsTest() = RoutingItemServer.testBlocking(settings = {}) {
    check(RoutingItemServer.replace.test("42").body?.text() == "Replaced 42")
    check(RoutingItemServer.update.test("42").body?.text() == "Updated 42")
    check(RoutingItemServer.remove.test("42").body?.text() == "Deleted 42")
}
```

## Reading the request

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-request -->
```kotlin
object RoutingSearchServer : ServerBuilder() {
    // GET /search?q=...&limit=...
    val search = path.path("search").get bind HttpHandler { request ->
        val query = request.queryParameters["q"] ?: throw BadRequestException("Missing q")
        val limit = request.queryParameters["limit"]?.toIntOrNull() ?: 10
        val agent = request.headers[HttpHeader.UserAgent]?.root ?: "unknown"
        HttpResponse.plainText("q=$query limit=$limit agent=$agent")
    }

    // POST /echo — reads the raw body
    val echo = path.path("echo").post bind HttpHandler { request ->
        val text = request.body?.text() ?: throw BadRequestException("Missing body")
        HttpResponse.plainText(text)
    }
}
```

`HttpRequest` properties:

| Property | Type | Notes |
|---|---|---|
| `path` | `RawHttpEndpoint<PATH>` | `arg1`..`arg3`, `trailingSegments`, `method` |
| `queryParameters` | `QueryParameters` | `["key"]` gives the first value or `null`; it is also a `List<Pair<String, String>>` for repeated keys |
| `headers` | `HttpHeaders` | Case-insensitive.  `[name]` and `getMany(name)` return `HttpHeaderValue`s (`.root`, `.parameters`); `contentType`, `accept`, `contentLength`, `cookies` are parsed for you |
| `body` | `TypedData?` | `text()`, or `parse(serializer)` (see [Serialization](serialization.md)) |
| `domain`, `protocol`, `sourceIp` | `String` | As the engine saw them |
| `cache` | `SerializableCache` | Request-scoped values, such as the resolved authentication |

Use the `HttpHeader` constants (`HttpHeader.Authorization`, `HttpHeader.ContentType`, ...) rather than string
literals.  To decode all query parameters into a data class, see
[Serialization](serialization.md#query-parameters).

In tests, pass query parameters, headers, and a body to `.test()`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-request-test -->
```kotlin
fun routingRequestTest() = RoutingSearchServer.testBlocking(settings = {}) {
    val found = RoutingSearchServer.search.test(
        queryParameters = QueryParameters.parse("q=lightning&limit=5"),
        headers = HttpHeaders(HttpHeader.UserAgent to "test-client"),
    )
    check(found.body?.text() == "q=lightning limit=5 agent=test-client")

    val echoed = RoutingSearchServer.echo.test(body = TypedData.text("ping", MediaType.Text.Plain))
    check(echoed.body?.text() == "ping")
}
```

## Building responses

`HttpResponse` is a body, a status, and headers.  The status defaults to 200 when there is a body and 204 when there
isn't; set it explicitly for anything else.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-response -->
```kotlin
object RoutingResponseServer : ServerBuilder() {
    val create = path.path("notes").post bind HttpHandler {
        HttpResponse(
            body = TypedData.text("""{"id":"n1"}""", MediaType.Application.Json),
            status = HttpStatus.Created,
            headers = HttpHeaders {
                add(HttpHeader.Location, "/notes/n1")
                setCookie("lastNote", "n1", maxAge = 3600, httpOnly = true, secure = true, sameSite = HttpHeaders.SameSite.Lax)
            },
        )
    }

    val page = path.path("about").get bind HttpHandler {
        HttpResponse.html {
            body { +"About us" }
        }
    }

    val old = path.path("old-about").get bind HttpHandler {
        HttpResponse.pathMovedPermanently("/about")
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-response-test -->
```kotlin
fun routingResponseTest() = RoutingResponseServer.testBlocking(settings = {}) {
    val created = RoutingResponseServer.create.test()
    check(created.status == HttpStatus.Created)
    check(created.headers[HttpHeader.Location]?.root == "/notes/n1")

    check(RoutingResponseServer.old.test().status == HttpStatus.PermanentRedirect)
}
```

Shortcuts on `HttpResponse`:

| Function | Status |
|---|---|
| `plainText(text)` | 200, `text/plain` |
| `html { ... }` / `html(content = "...")` | 200, `text/html`, kotlinx.html DSL or a string |
| `redirectToGet(to)` | 303 See Other |
| `pathMoved(to)` | 307 Temporary Redirect |
| `pathMovedPermanently(to)` | 308 Permanent Redirect |
| `pathMovedOld(to)` | 302 Found |

A redirect target starting with `/` gets the path prefix of the server's public URL, if it has one (a `publicUrl` of
`https://example.com/api` turns `/about` into `/api/about`).

Bodies are `TypedData`: `TypedData.text(text, mediaType)`, `TypedData.bytes(bytes, mediaType)`, or
`TypedData.source(source, mediaType)` to stream from a kotlinx-io `Source` without loading it into memory.  To encode a
`@Serializable` value according to the client's `Accept` header, use `value.toTypedData(request.headers.accept)`
([Serialization](serialization.md)).

`HttpHeaders { }` builds headers: `add(name, value)`, `remove(name)`, and `setCookie(name, value, expiresAt, maxAge,
domain, path, secure, httpOnly, sameSite)`.  `headers.copy { }` and `a + b` derive new headers from existing ones.
`HttpStatus` has named constants for the standard codes (`HttpStatus.Conflict`, `HttpStatus.TooManyRequests`, ...) and
`HttpStatus(418)` for anything else.

## Grouping endpoints

Put related endpoints in their own `ServerBuilder` and mount it with `include`.  Its paths become relative to the
mount point:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-include -->
```kotlin
object RoutingCommentsApi : ServerBuilder() {
    // GET /comments, relative to wherever this builder is mounted
    val list = path.path("comments").get bind HttpHandler {
        HttpResponse.plainText("[]")
    }
}

object RoutingBlogServer : ServerBuilder() {
    // Mounts RoutingCommentsApi.list at GET /blog/comments
    val comments = path.path("blog") include RoutingCommentsApi
}
```

Test the included endpoints through their own references, inside the root server's test runner:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-include-test -->
```kotlin
fun routingIncludeTest() = RoutingBlogServer.testBlocking(settings = {}) {
    check(RoutingCommentsApi.list.test().body?.text() == "[]")
}
```

`include` needs a path without arguments.  To make the group a named namespace in the generated client, mount it with
`module` instead ([Client SDKs](client-sdks.md#sdk-modules)).

## Timeouts

Handlers time out after 30 seconds unless you pass a different `timeout`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-timeout -->
```kotlin
object RoutingReportServer : ServerBuilder() {
    val report = path.path("report").get bind HttpHandler(timeout = 5.minutes) {
        HttpResponse.plainText("done")
    }
}
```

## Gotchas

- Three typed path arguments is the limit.  A fourth `.arg` yields an untyped `PathSpecMany`.
- Handlers throw to fail: `throw BadRequestException("...")` becomes a 400 with a JSON error body.  See
  [Error handling](error-handling.md).
- Cross-cutting behavior (CORS, logging, API keys) belongs in interceptors, not in each handler.  See
  [CORS and interceptors](cors-interceptors.md).
- Handlers run with a `ServerRuntime` in context, so settings and services (`database()`, `cache()`) are callable
  directly.  See [Services](services.md).

## Next

- [Typed endpoints](typed-endpoints.md): JSON in and out, documentation, and generated clients.
- [WebSockets](websockets.md): the same path syntax for sockets.
- [Testing](testing.md): the `testBlocking` and `.test()` pattern in full.
