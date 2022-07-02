package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.application.*
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

        val myPosts = Post.mongo.forUser(me)
        val dansPosts = Post.mongo.forUser(dan)

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
        checkCondition(startChain<Post>().content eq "Joe post")
        checkCondition(startChain<Post>().content neq "Joe post")
        checkCondition(startChain<Post>().content inside listOf("Joe post", "Dan post"))
        checkCondition(startChain<Post>().content nin listOf("Joe post", "Dan post"))
        checkCondition(startChain<Post>().content gt "Joe post")
        checkCondition(startChain<Post>().content lt "Joe post")
        checkCondition(startChain<Post>().content gte "Joe post")
        checkCondition(startChain<Post>().content lte "Joe post")
    }
}