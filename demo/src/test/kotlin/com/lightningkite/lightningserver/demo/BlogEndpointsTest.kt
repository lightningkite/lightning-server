package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.ai.SystemChatConversation
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.demo.Server.UserAuth
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.insertOne
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class BlogEndpointsTest {
    @Test
    fun toolInfo() {
        TestHelper.testServer {
            runBlocking {
                val user = User(
                    email = "test@example.com",
                    hashedPassword = "hashed_password",
                    isSuperUser = false
                )
                userInfo.table().insertOne(user)
                val a = AuthAccess(UserAuth.testAuth(user))
                blogAssist.tools.entries.forEach { (string, tool) ->
                    println("$string: ${tool.koogDescriptor(a)}")
                    println("Shared: ${tool.description(a).sharedExplanations.joinToString("\n") { it.render() }}")
                }
            }
        }
    }

    @Test
    fun wholePrompt() {
        TestHelper.testServer {
            runBlocking {
                val user = User(
                    email = "test@example.com",
                    hashedPassword = "hashed_password",
                    isSuperUser = false
                )
                userInfo.table().insertOne(user)
                val a = AuthAccess(UserAuth.testAuth(user))
                println(blogAssist.getPrompt(
                    SystemChatConversation(subjectId = user._id.toString(), createdAt = now()),
                    auth = a
                ))
            }
        }
    }
}