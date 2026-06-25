package com.lightningkite.lightningserver.guide.samples

// region mr-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlin.uuid.*
// endregion mr-imports

// region mr-model
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
) : HasId<Uuid>
// endregion mr-model

// region mr-server
object PostRestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    // modelInfo<USER, Model, ID>:
    //   USER = HasId<*>? — the noAuth "user" type (no authenticated caller)
    //   Model = Post, the document type stored in the database
    //   ID = Uuid, the primary-key type
    val postInfo = database.modelInfo<HasId<*>?, Post, Uuid>(
        auth = noAuth,
        permissions = { ModelPermissions.allowAll() }
    )

    // include mounts all generated endpoints under /posts
    val posts = path.path("posts") include ModelRestEndpoints(postInfo)
}
// endregion mr-server

// region mr-test
fun modelRestTest() = PostRestServer.testBlocking(settings = { database set Database.Settings("ram") }) {
    // Insert a post — returns the stored copy
    val post = PostRestServer.posts.insert.test(null, Post(title = "Hello", author = "alice", body = "First post"))
    check(post.title == "Hello")

    // Retrieve by ID: first argument is the path arg (ID), second is auth, third is the body
    val fetched = PostRestServer.posts.detail.test(post._id, null, Unit)
    check(fetched._id == post._id)
    check(fetched.title == "Hello")

    // List uses a Query; Condition.Always matches every document
    val all = PostRestServer.posts.list.test(null, Query(Condition.Always))
    check(all.size == 1)

    // Modify: apply a field-level update and receive the new document
    val modified = PostRestServer.posts.modify.test(
        post._id, null,
        modification { it.title assign "Updated Title" }
    )
    check(modified.title == "Updated Title")
    check(modified.author == "alice")  // unchanged

    // Delete by ID
    PostRestServer.posts.deleteItem.test(post._id, null, Unit)

    // Count confirms the document is gone
    val remaining = PostRestServer.posts.count.test(null, Condition.Always)
    check(remaining == 0)
}
// endregion mr-test
