package com.lightningkite.lightningserver.websocket

import com.lightningkite.UUID
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.serialization.TypeRetriever
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketsTest {
    open class TestMirrorSocket() : WebSocketHandler<String> {
        var connects = 0
        var messages = 0
        var disconnects = 0
        var wssubs = 0
        fun resetCounts() {
            connects = 0
            messages = 0
            disconnects = 0
            wssubs = 0
        }

        fun assertCounts(connects: Int, messages: Int, disconnects: Int) {
            assertEquals("connects mismatch", connects, this.connects)
            assertEquals("messages mismatch", messages, this.messages)
            assertEquals("disconnects mismatch", disconnects, this.disconnects)
        }

        override val storageSerializer: KSerializer<String> = String.serializer()

        override suspend fun willConnect(request: WebSocketConnectRequest): String = UUID.random().toString()

        override suspend fun didConnect(connection: MidWebsocket<String>, request: WebSocketConnectRequest) {
            println("${connection.currentState} - connects: ${++connects}")
        }

        override suspend fun messageFromClient(
            connection: MidWebsocket<String>,
            frame: WebSocketFrame
        ) {
            println("${connection.currentState} - messages: ${++messages}")
            connection.send(frame)
        }

        override suspend fun messageFromSubscription(
            connection: MidWebsocket<String>,
            topic: String,
            retrieve: TypeRetriever
        ) {
            println("${connection.currentState} - wssub: ${++wssubs}")
        }

        override suspend fun disconnect(connection: MidWebsocket<String>, reason: WebSocketClose) {
            println("${connection.currentState} - disconnects: ${++disconnects}")
        }
    }

    @Test
    fun testerWorksNormally() {
        val mirror = TestMirrorSocket()
        val ws = ServerPath.root.path("test").websocket(mirror)
        runBlocking {
            ws.test {
                this.send("test")
                this.incoming.receive()
                println("OK done")
            }
            mirror.assertCounts(1, 1, 1)
        }
    }

    @Test
    fun testerExceptionCausesDisconnect() {
        val mirror = object : TestMirrorSocket() {
            override suspend fun messageFromClient(connection: MidWebsocket<String>, frame: WebSocketFrame) {
                super.messageFromClient(connection, frame)
                throw Exception()
            }
        }
        val ws = ServerPath.root.path("test").websocket(mirror)
        runBlocking {
            try {
                ws.test {
                    this.send("will fail")
                }
            } catch (e: Exception) { /*squish*/
                e.printStackTrace()
            }
            mirror.assertCounts(1, 1, 1)
        }
    }
}