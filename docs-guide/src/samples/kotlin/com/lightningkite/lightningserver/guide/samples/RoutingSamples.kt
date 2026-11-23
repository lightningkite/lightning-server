package com.lightningkite.lightningserver.guide.samples

// region routing-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.data.*
import kotlinx.html.*
import kotlin.time.Duration.Companion.minutes
// endregion routing-imports

// region routing-minimal
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
// endregion routing-minimal

// region routing-minimal-test
fun routingMinimalTest() = RoutingServer.testBlocking(settings = {}) {
    check(RoutingServer.hello.test().body?.text() == "Hello!")
    check(RoutingServer.user.test(42).body?.text() == "User 42")
}
// endregion routing-minimal-test

// region routing-methods
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
// endregion routing-methods

// region routing-methods-test
fun routingMethodsTest() = RoutingItemServer.testBlocking(settings = {}) {
    check(RoutingItemServer.replace.test("42").body?.text() == "Replaced 42")
    check(RoutingItemServer.update.test("42").body?.text() == "Updated 42")
    check(RoutingItemServer.remove.test("42").body?.text() == "Deleted 42")
}
// endregion routing-methods-test

// region routing-multi-arg
object RoutingPostServer : ServerBuilder() {
    // GET /users/{userId}/posts/{postId}
    val getPost = path.path("users").arg<String>("userId")
        .path("posts").arg<Int>("postId").get bind HttpHandler { request ->
            HttpResponse.plainText("User ${request.path.arg1}, post ${request.path.arg2}")
        }
}
// endregion routing-multi-arg

// region routing-multi-arg-test
fun routingMultiArgTest() = RoutingPostServer.testBlocking(settings = {}) {
    check(RoutingPostServer.getPost.test("alice", 7).body?.text() == "User alice, post 7")
}
// endregion routing-multi-arg-test

// region routing-trailing
object RoutingFilesServer : ServerBuilder() {
    // GET /files/{...} matches /files/a, /files/a/b/c, ...
    val file = path.path("files").any.get bind HttpHandler { request ->
        HttpResponse.plainText("File ${request.path.trailingSegments}")
    }
}
// endregion routing-trailing

// region routing-trailing-test
fun routingTrailingTest() = RoutingFilesServer.testBlocking(settings = {}) {
    val response = RoutingFilesServer.file.test(trailingWildcard = PathSegments(listOf("docs", "readme.txt")))
    check(response.body?.text() == "File docs/readme.txt")
}
// endregion routing-trailing-test

// region routing-request
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
// endregion routing-request

// region routing-request-test
fun routingRequestTest() = RoutingSearchServer.testBlocking(settings = {}) {
    val found = RoutingSearchServer.search.test(
        queryParameters = QueryParameters.parse("q=lightning&limit=5"),
        headers = HttpHeaders(HttpHeader.UserAgent to "test-client"),
    )
    check(found.body?.text() == "q=lightning limit=5 agent=test-client")

    val echoed = RoutingSearchServer.echo.test(body = TypedData.text("ping", MediaType.Text.Plain))
    check(echoed.body?.text() == "ping")
}
// endregion routing-request-test

// region routing-response
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
// endregion routing-response

// region routing-response-test
fun routingResponseTest() = RoutingResponseServer.testBlocking(settings = {}) {
    val created = RoutingResponseServer.create.test()
    check(created.status == HttpStatus.Created)
    check(created.headers[HttpHeader.Location]?.root == "/notes/n1")

    check(RoutingResponseServer.old.test().status == HttpStatus.PermanentRedirect)
}
// endregion routing-response-test

// region routing-include
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
// endregion routing-include

// region routing-include-test
fun routingIncludeTest() = RoutingBlogServer.testBlocking(settings = {}) {
    check(RoutingCommentsApi.list.test().body?.text() == "[]")
}
// endregion routing-include-test

// region routing-timeout
object RoutingReportServer : ServerBuilder() {
    val report = path.path("report").get bind HttpHandler(timeout = 5.minutes) {
        HttpResponse.plainText("done")
    }
}
// endregion routing-timeout
