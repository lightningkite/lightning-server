package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class QueryParamWebSocketHandlerTest {
    @Test
    fun test() {
        val firstPath = ServerPath.root.path("first")
        val first = firstPath.websocket(
            willConnect = {
                assertEquals(firstPath, it.path)
                println("Connect first")
            },
            message = {
                assertEquals(firstPath, request.path)
                println("Message first"); send("Reply first")
            },
            disconnect = {
                assertEquals(firstPath, request.path)
                println("Disconnect first")
            }
        )
        val secondPath = ServerPath.root.path("second/{part}")
        val second = secondPath.websocket(
            willConnect = {
                assertEquals(secondPath, it.path)
                println("Connect second ${it.parts["part"]}")
            },
            message = {
                assertEquals(secondPath, request.path)
                assertEquals(mapOf("part" to "test"), request.parts)
                println("Message second"); send("Reply second")
            },
            disconnect = {
                assertEquals(secondPath, request.path)
                assertEquals(mapOf("part" to "test"), request.parts)
                println("Disconnect second")
            }
        )
        val target = ServerPath.root.path("qp").websocket(QueryParamWebSocketHandler())
        runBlocking {
            target.test(queryParameters = listOf("path" to first.toString())) {
                this.send("Sending first")
                println(this.incoming.receive())
            }
            target.test(queryParameters = listOf("path" to second.toString(mapOf("part" to "test")))) {
                this.send("Sending second")
                println(this.incoming.receive())
            }
        }
    }

    @Test
    fun testAdditionalParams() {
        val first = ServerPath.root.path("first").websocket(
            willConnect = { println("Connect first; qp: ${it.queryParameters}") },
            message = { println("Message first"); send("Reply first") },
            disconnect = { println("Disconnect first") }
        )
        val target = ServerPath.root.path("qp").websocket(QueryParamWebSocketHandler())
        runBlocking {
            target.test(queryParameters = listOf("path" to "$first?first=first", "second" to "second")) {
                this.send("Sending first")
                println(this.incoming.receive())
            }
        }
    }
}