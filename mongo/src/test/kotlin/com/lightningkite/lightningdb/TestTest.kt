package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.application.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger


class TestTest: MongoTest() {
    @Test
    fun test(): Unit = runBlocking {
        val me = User(email = "joseph@lightningkite.com",)
        User.mongo.insertOne(me)
        val dan = User(email = "dan@lightningkite.com",)
        User.mongo.insertOne(dan)

        val myPosts = Post.mongo.forUser(me)
        val dansPosts = Post.mongo.forUser(dan)

        myPosts.insertOne(Post(author = me._id, content = "Joe post"))
        dansPosts.insertOne(Post(author = dan._id, content = "Dan post"))
        myPosts.insertOne(Post(author = me._id, content = "Another Joe Post"))
        myPosts.updateManyIgnoringResult(
            startChain<Post>().content eq "Joe post",
            startChain<Post>().content assign "Joe post updated"
        )
    }
}