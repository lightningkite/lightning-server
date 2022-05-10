package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import com.lightningkite.ktordb.Query
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class FiltersTest: MongoTest() {
    @Test
    fun test(): Unit = runBlocking {
        val me = User(email = "joseph@lightningkite.com",)
        User.mongo.insertOne(me)
        val dan = User(email = "dan@lightningkite.com",)
        User.mongo.insertOne(dan)

        val myPosts = Post.mongo.secure(Post.secure(me))
        val dansPosts = Post.mongo.secure(Post.secure(dan))

        myPosts.insertOne(Post(author = me._id, content = "Joe post"))
        myPosts.insertOne(Post(author = me._id, content = "Alphabetical post"))
        myPosts.insertOne(Post(author = me._id, content = "Zero is where arrays start"))
        dansPosts.insertOne(Post(author = dan._id, content = "Dan post"))
        dansPosts.insertOne(Post(author = dan._id, content = "Servers are great"))

        val allPosts = myPosts.query(Query()).toList()

        suspend fun checkCondition(condition: Condition<Post>) {
            val fromDb = myPosts.query(Query(condition)).toList().sortedBy { it.content }
            val filtered = allPosts.filter { condition(it) }.sortedBy { it.content }
            assertEquals(filtered, fromDb, "Condition ${condition.bson().toJson()} failed")
        }
        checkCondition(Post.chain.content eq "Joe post")
        checkCondition(Post.chain.content neq "Joe post")
        checkCondition(Post.chain.content inside listOf("Joe post", "Dan post"))
        checkCondition(Post.chain.content nin listOf("Joe post", "Dan post"))
        checkCondition(Post.chain.content gt "Joe post")
        checkCondition(Post.chain.content lt "Joe post")
        checkCondition(Post.chain.content gte "Joe post")
        checkCondition(Post.chain.content lte "Joe post")
    }
}