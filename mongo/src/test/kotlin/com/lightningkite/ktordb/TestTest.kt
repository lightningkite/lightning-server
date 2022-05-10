package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
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

        val myPosts = Post.mongo.secure(Post.secure(me))
        val dansPosts = Post.mongo.secure(Post.secure(dan))

        val listenerCount = 100
        val expectedEvents = 3
        val changeCount = AtomicInteger()
        val started = AtomicInteger()

        val listeners = (0 until listenerCount).map { GlobalScope.launch {
            var myCount = 0
            try {
                myPosts.watch(Post.chain.let { it.author eq me._id })
                    .onStart {
                        started.incrementAndGet()
                    }.collect {
                    changeCount.incrementAndGet()
                    myCount++
                }
            } catch(e: CancellationException) {
                println("Cancelled $it, got ${expectedEvents}")
            }
        } }
        repeat(4) {
            delay(100L)
        }

        myPosts.insertOne(Post(author = me._id, content = "Joe post"))
        dansPosts.insertOne(Post(author = dan._id, content = "Dan post"))
        myPosts.insertOne(Post(author = me._id, content = "Another Joe Post"))
        myPosts.updateMany(
            Post.chain.content eq "Joe post",
            Post.chain.content assign "Joe post updated"
        )
        repeat(20) {
            delay(5L)
        }
        listeners.forEach { it.cancelAndJoin() }
//        measureNanoTime {
//            repeat(1000) {
//                println("Iter $it")
//                myPosts.insertOne(Post(author = me._id, content = "Test Description 2"))
//                myPosts.find(PostFields.always()).collect {  }
//            }
//        }.let { println("${it / 1_000_000} millis") }
    }
}