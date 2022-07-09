//package com.lightningkite.lightningdb
//
//import com.lightningkite.lightningdb.application.*
//import kotlinx.coroutines.*
//import kotlinx.coroutines.flow.collect
//import org.junit.Test


//class SecurityTests : MongoTest() {
//    val user1 = User(email = "user1@lightningkite.com",)
//    val otherUser = User(email = "otherUser@lightningkite.com",)
//
//    lateinit var user1Jwt: String
//    lateinit var otherUserJwt: String


//    @Test
//    fun test(): Unit = runBlocking {
//        val myPosts = Post.mongo.forUser(user1)
//        val dansPosts = Post.mongo.forUser(otherUser)
//        GlobalScope.launch {
//            myPosts.watch(startChain<Post>().let { startChain<Post>().author eq user1._id }).collect {
//            }
//        }
//        delay(1000L)
//        myPosts.insertOne(Post(author = user1._id, content = "User1 post"))
//        dansPosts.insertOne(Post(author = otherUser._id, content = "OtherUser post"))
//        myPosts.insertOne(Post(author = user1._id, content = "Another User1 Post"))
//        myPosts.updateManyIgnoringResult(
//            startChain<Post>().content eq "User1 post",
//            startChain<Post>().content assign "User1 post updated"
//        )
//        delay(1000L)
//    }

//}
