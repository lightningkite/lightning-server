package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.ServerPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebSocketsTest {
    open class TestMirrorSocket(): WebSockets.Handler {
        var connects = 0
        var messages = 0
        var disconnects = 0
        fun resetCounts() {
            connects = 0
            messages = 0
            disconnects = 0
        }
        fun assertCounts(connects: Int, messages: Int, disconnects: Int) {
            assertEquals(connects, this.connects)
            assertEquals(messages, this.messages)
            assertEquals(disconnects, this.disconnects)
        }

        override suspend fun connect(event: WebSockets.ConnectEvent) {
            println("${event.id} - connects: ${++connects}")
        }

        override suspend fun message(event: WebSockets.MessageEvent) {
            println("${event.id} - messages: ${++messages}")
            event.id.send(event.content)
        }

        override suspend fun disconnect(event: WebSockets.DisconnectEvent) {
            println("${event.id} - disconnects: ${++disconnects}")
        }
    }

    @Test
    fun testerWorksNormally() {
        val mirror = TestMirrorSocket()
        val ws = ServerPath.root.path("test").websocket(mirror)
        runBlocking {
            try {
                ws.test {
                    this.send("test")
                    this.incoming.receive()
                }
            } catch(e: Exception) { /*squish*/ }
            mirror.assertCounts(1, 1, 1)
        }
    }

    @Test
    fun testerExceptionCausesDisconnect() {
        val mirror = object: TestMirrorSocket() {
            override suspend fun message(event: WebSockets.MessageEvent) {
                super.message(event)
                throw Exception()
            }
        }
        val ws = ServerPath.root.path("test").websocket(mirror)
        runBlocking {
            try {
                ws.test {
                    this.send("will fail")
                }
            } catch(e: Exception) { /*squish*/ }
            mirror.assertCounts(1, 1, 1)
        }
    }
}