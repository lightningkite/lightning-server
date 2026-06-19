# Routing & Path Parameters

This chapter covers the building blocks for structuring URL routes: nested
paths, every supported HTTP method, multiple typed path arguments, grouping
endpoints into sub-builders, and reading query parameters.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#routing-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import kotlinx.coroutines.*
```

## Nested Paths

Chain `.path("segment")` calls to build multi-segment URLs.  There is no
limit to how many segments you can nest:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#nested-paths -->
```kotlin
object NestedServer : ServerBuilder() {

    // GET /api/v1/status
    val status = path.path("api").path("v1").path("status").get bind HttpHandler {
        HttpResponse.plainText("OK")
    }
}
```

The endpoint is mounted at the full path `/api/v1/status`.  Test it exactly
as you would a root endpoint — the path is resolved automatically.

> To wrap these examples in a test class, annotate your test methods with `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` + `runBlocking` pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#nested-paths-test -->
```kotlin
fun nestedPathsTest() = runBlocking {
    NestedServer.test(settings = {}) {
        val response = NestedServer.status.test()
        check(response.body?.text() == "OK")
    }
}
```

## HTTP Methods

`.get` and `.post` were shown in Chapter 1.  Lightning Server also provides
`.put`, `.patch`, and `.delete` as extension properties on any path spec,
covering the full REST surface:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#http-methods -->
```kotlin
object ItemServer : ServerBuilder() {

    // PUT /items/{id} — replace an item entirely
    val replace = path.path("items").arg<String>("id").put bind HttpHandler { request ->
        HttpResponse.plainText("Replaced ${request.path.arg1}")
    }

    // PATCH /items/{id} — update fields on an item
    val update = path.path("items").arg<String>("id").patch bind HttpHandler { request ->
        HttpResponse.plainText("Updated ${request.path.arg1}")
    }

    // DELETE /items/{id} — remove an item
    val remove = path.path("items").arg<String>("id").delete bind HttpHandler { request ->
        HttpResponse.plainText("Deleted ${request.path.arg1}")
    }
}
```

Each method is a separate endpoint registered on the same path.  `.test()`
accepts the path argument as its first positional parameter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#http-methods-test -->
```kotlin
fun httpMethodsTest() = runBlocking {
    ItemServer.test(settings = {}) {
        val replaced = ItemServer.replace.test("42")
        check(replaced.body?.text() == "Replaced 42")

        val updated = ItemServer.update.test("42")
        check(updated.body?.text() == "Updated 42")

        val deleted = ItemServer.remove.test("42")
        check(deleted.body?.text() == "Deleted 42")
    }
}
```

## Multiple Path Arguments

Chain `.arg<T>("name")` calls for each variable segment.  Access the parsed
values as `request.path.arg1`, `request.path.arg2`, etc.:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#multi-arg-server -->
```kotlin
object PostServer : ServerBuilder() {

    // GET /users/{userId}/posts/{postId}
    // arg<String> produces PathSpec1<String>; chaining arg<Int> produces PathSpec2<String, Int>.
    // Access the first argument as arg1 and the second as arg2.
    val getPost = path.path("users").arg<String>("userId")
        .path("posts").arg<Int>("postId").get bind HttpHandler { request ->
        val userId = request.path.arg1
        val postId = request.path.arg2
        HttpResponse.plainText("User $userId, post $postId")
    }
}
```

Pass both arguments to `.test()` in order:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#multi-arg-test -->
```kotlin
fun multiArgTest() = runBlocking {
    PostServer.test(settings = {}) {
        val response = PostServer.getPost.test("alice", 7)
        check(response.body?.text() == "User alice, post 7")
    }
}
```

The framework coerces the `Int` argument to and from its URL string
representation automatically; the handler receives the correctly typed value.

## Grouping Endpoints with `include`

As your server grows, grouping related endpoints into a separate `ServerBuilder`
keeps each object focused.  Mount it with `include`, which is an infix member
of `ServerBuilder` — no import needed.  The receiver must be a bare path (a
`PathSpec0`), meaning you cannot `include` after `.arg<T>()`.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#sub-builder -->
```kotlin
// Declare API endpoints in a separate ServerBuilder object for clarity.
object CommentsApi : ServerBuilder() {
    val list = path.path("comments").get bind HttpHandler {
        HttpResponse.plainText("[]")
    }
}

object BlogServer : ServerBuilder() {
    // Mount CommentsApi at /blog — all its paths are prefixed with /blog.
    // include is a ServerBuilder member; the left-hand side must be a PathSpec0.
    val comments = path.path("blog") include CommentsApi
}
```

The endpoints of `CommentsApi` are registered under the `BlogServer` hierarchy.
In tests, drive them via `CommentsApi`'s own endpoint references — the path
prefix is applied automatically by the `BlogServer.test {}` runner:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#sub-builder-test -->
```kotlin
fun subBuilderTest() = runBlocking {
    BlogServer.test(settings = {}) {
        val response = CommentsApi.list.test()
        check(response.body?.text() == "[]")
    }
}
```

## Query Parameters

Read query parameters from `request.queryParameters["key"]`.  The result is
`String?` — it is `null` when the key is absent.  Provide a default or convert
with the standard Kotlin nullable operators:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#query-params -->
```kotlin
object SearchServer : ServerBuilder() {

    // GET /search?q=...&limit=...
    val search = path.path("search").get bind HttpHandler { request ->
        val query = request.queryParameters["q"] ?: ""
        val limit = request.queryParameters["limit"]?.toIntOrNull() ?: 10
        HttpResponse.plainText("query=$query limit=$limit")
    }
}
```

Pass query parameters in tests via `QueryParameters.parse("key=value&...")`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/RoutingSamples.kt#query-params-test -->
```kotlin
fun queryParamsTest() = runBlocking {
    SearchServer.test(settings = {}) {
        val response = SearchServer.search.test(
            queryParameters = QueryParameters.parse("q=lightning&limit=5")
        )
        check(response.body?.text() == "query=lightning limit=5")
    }
}
```

## What's Next

- **Typed endpoints with error cases** — use `ApiHttpHandler` (introduced in
  Chapter 1) to add automatic JSON serialisation, documentation, and typed
  error responses to any of the routes above.
- **Authentication** — swap `noAuth` for `authOptions<YourUser>()` and use
  `request.auth` inside the handler to access the validated session.
- **Services** — the handler lambda has access to a `ServerRuntime` context;
  call `database()`, `cache()`, or any other configured service from there.
