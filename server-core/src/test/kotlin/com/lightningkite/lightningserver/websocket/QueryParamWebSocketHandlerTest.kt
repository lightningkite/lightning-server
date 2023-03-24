package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueryParamWebSocketHandlerTest {
    @Test
    fun test() {
        val first = ServerPath.root.path("first").websocket(
            connect = { println("Connect first") },
            message = { println("Message first"); it.id.send("Reply first") },
            disconnect = { println("Disconnect first") }
        )
        val second = ServerPath.root.path("second/{part}").websocket(
            connect = { println("Connect second ${it.parts["part"]}") },
            message = { println("Message second"); it.id.send("Reply second") },
            disconnect = { println("Disconnect second") }
        )
        val target = ServerPath.root.path("qp").websocket(QueryParamWebSocketHandler { LocalCache })
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
            connect = { println("Connect first; qp: ${it.queryParameters}") },
            message = { println("Message first"); it.id.send("Reply first") },
            disconnect = { println("Disconnect first") }
        )
        val target = ServerPath.root.path("qp").websocket(QueryParamWebSocketHandler { LocalCache })
        runBlocking {
            target.test(queryParameters = listOf("path" to "$first?first=first", "second" to "second")) {
                this.send("Sending first")
                println(this.incoming.receive())
            }
        }
    }
}