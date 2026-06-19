package com.lightningkite.lightningserver.guide.samples

// region routing-imports
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.delete
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.patch
import com.lightningkite.lightningserver.http.put
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.pathing.arg2
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
// endregion routing-imports

// region nested-paths
object NestedServer : ServerBuilder() {

    // GET /api/v1/status
    val status = path.path("api").path("v1").path("status").get bind HttpHandler {
        HttpResponse.plainText("OK")
    }
}
// endregion nested-paths

// region nested-paths-test
fun nestedPathsTest() = runBlocking {
    NestedServer.test(settings = {}) {
        val response = NestedServer.status.test()
        check(response.body?.text() == "OK")
    }
}
// endregion nested-paths-test

// region http-methods
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
// endregion http-methods

// region http-methods-test
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
// endregion http-methods-test

// region multi-arg-server
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
// endregion multi-arg-server

// region multi-arg-test
fun multiArgTest() = runBlocking {
    PostServer.test(settings = {}) {
        val response = PostServer.getPost.test("alice", 7)
        check(response.body?.text() == "User alice, post 7")
    }
}
// endregion multi-arg-test

// region sub-builder
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
// endregion sub-builder

// region sub-builder-test
fun subBuilderTest() = runBlocking {
    BlogServer.test(settings = {}) {
        val response = CommentsApi.list.test()
        check(response.body?.text() == "[]")
    }
}
// endregion sub-builder-test

// region query-params
object SearchServer : ServerBuilder() {

    // GET /search?q=...&limit=...
    val search = path.path("search").get bind HttpHandler { request ->
        val query = request.queryParameters["q"] ?: ""
        val limit = request.queryParameters["limit"]?.toIntOrNull() ?: 10
        HttpResponse.plainText("query=$query limit=$limit")
    }
}
// endregion query-params

// region query-params-test
fun queryParamsTest() = runBlocking {
    SearchServer.test(settings = {}) {
        val response = SearchServer.search.test(
            queryParameters = QueryParameters.parse("q=lightning&limit=5")
        )
        check(response.body?.text() == "query=lightning limit=5")
    }
}
// endregion query-params-test
