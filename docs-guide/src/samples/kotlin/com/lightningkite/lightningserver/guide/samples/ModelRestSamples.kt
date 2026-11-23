package com.lightningkite.lightningserver.guide.samples

// region mr-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlin.uuid.*
// endregion mr-imports

// region mr-model
@Queryable
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
) : HasId<Uuid>
// endregion mr-model

// region mr-server
object PostRestServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        tableName = "Post",
        permissions = { ModelPermissions.allowAll() },
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
// endregion mr-server

// region mr-custom
object PostStatsServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        tableName = "Post",
        permissions = { ModelPermissions(read = Condition.Always, manage = Condition.Never) },
    )

    val posts = path.path("posts") include ModelRestEndpoints(postInfo)

    val postsPerAuthor = path.path("post-stats").get bind ApiHttpHandler(
        summary = "Count posts per author",
        auth = postInfo.auth,
        implementation = { _: Unit ->
            postInfo.table(this).groupCount(groupBy = Post.author)
        }
    )
}
// endregion mr-custom

// region mr-test
fun modelRestTest() = PostRestServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    val posts = PostRestServer.posts

    val post = posts.insert.test(null, Post(title = "Hello", author = "alice", body = "First post"))

    val fetched = posts.detail.test(post._id, null, Unit)
    val page = posts.query.test(null, Query(Post.author eq "alice", limit = 20))
    val modified = posts.modify.test(post._id, null, Modification { it.title assign "Updated" })
    posts.deleteItem.test(post._id, null, Unit)
    val remaining = posts.count.test(null, Condition.Always)

    check(fetched == post)
    check(page == listOf(post))
    check(modified.title == "Updated" && modified.body == "First post")
    check(remaining == 0)
}
// endregion mr-test

fun modelRestCustomTest() = PostStatsServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    execute {
        PostStatsServer.postInfo.table().insert(
            listOf(
                Post(title = "A", author = "alice", body = ""),
                Post(title = "B", author = "alice", body = ""),
                Post(title = "C", author = "bob", body = ""),
            )
        )
    }
    check(PostStatsServer.postsPerAuthor.test(null, Unit) == mapOf("alice" to 2, "bob" to 1))
    val rejected = runCatching { PostStatsServer.posts.insert.test(null, Post(title = "D", author = "eve", body = "")) }
    check(rejected.exceptionOrNull() is ForbiddenException) { rejected.toString() }
}
