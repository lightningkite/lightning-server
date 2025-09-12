package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.topic
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.deprecations.websocket
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.websockets.subscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class MultiplexWebSocketHandlerTest {

    object TestServer : ServerBuilder() {
        val broadcast = path.path("broadcast").topic(String.serializer())
        val mirror = path.path("mirror") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { subscribe(broadcast) },
            topicHandlers = {
                broadcast bind { send(WebSocketFrame(it.value)) }
            },
            messageFromClient = { frame -> send(frame) },
            disconnect = {}
        )
        val other = path.path("other") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = {},
            topicHandlers = {},
            messageFromClient = { frame ->
                if (frame is WebSocketFrame.Text) send(WebSocketFrame("other:" + frame.text))
                else send(frame)
            },
            disconnect = {}
        )
        val multiplex = path.path("multiplex").websocket(MultiplexWebSocketHandler())
    }

    @Test
    fun multiplex_basic_flow() = runBlocking {
        TestServer.test(settings = {}) {
                    val mux = TestServer.multiplex.test()
            val json = mux.server.externalSerialization.json
            var last: WebSocketFrame? = null
            mux.onMessageSent = { last = it }
            // Start channel a -> /mirror
            val startMirror = MultiplexMessage(channel = "a", path = "/mirror", start = true)
            mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), startMirror)))
            // server should acknowledge start
            val ack = last as WebSocketFrame.Text
            val ackMsg = json.decodeFromString(MultiplexMessage.serializer(), ack.text)
            assertEquals("a", ackMsg.channel)
            assertEquals(true, ackMsg.start)

            // Send normal message through channel a, expect mirrored back
            val data = MultiplexMessage(channel = "a", data = "ping")
            mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), data)))
            val echoed = last as WebSocketFrame.Text
            val echoedMsg = json.decodeFromString(MultiplexMessage.serializer(), echoed.text)
            assertEquals("a", echoedMsg.channel)
            assertEquals("ping", echoedMsg.data)

            // Start channel b -> /other
            val startOther = MultiplexMessage(channel = "b", path = "/other", start = true)
            mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), startOther)))
            // acknowledge
            val ack2 = json.decodeFromString(MultiplexMessage.serializer(), (last as WebSocketFrame.Text).text)
            assertEquals("b", ack2.channel)
            assertEquals(true, ack2.start)

            // Send to b and verify handler prefix applied
            val bData = MultiplexMessage(channel = "b", data = "x")
            mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), bData)))
            val bEcho = json.decodeFromString(MultiplexMessage.serializer(), (last as WebSocketFrame.Text).text)
            assertEquals("b", bEcho.channel)
            assertEquals("other:x", bEcho.data)

            // Broadcast topic should hit both mirror subscribers through multiplex wrapper
            TestServer.broadcast.send("topic!")
            val tEcho = json.decodeFromString(MultiplexMessage.serializer(), (last as WebSocketFrame.Text).text)
            // last received could be either for a or b depending on order; ensure it's one of them and content matches
            assert(listOf("a","b").contains(tEcho.channel))
            assertEquals("topic!", tEcho.data)

            // End channel a
            val endA = MultiplexMessage(channel = "a", end = true)
            mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), endA)))
            val endAck = json.decodeFromString(MultiplexMessage.serializer(), (last as WebSocketFrame.Text).text)
            assertEquals("a", endAck.channel)
            assertEquals(true, endAck.end)

            mux.close()
        }
    }
}
