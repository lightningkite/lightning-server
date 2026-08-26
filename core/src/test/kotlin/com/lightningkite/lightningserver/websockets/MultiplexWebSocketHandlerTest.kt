package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        val toggle = path.path("toggle") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { subscribe(broadcast) },
            topicHandlers = {
                broadcast bind { send(WebSocketFrame(it.value)) }
            },
            messageFromClient = { frame ->
                if (frame is WebSocketFrame.Text && frame.text == "off") unsubscribe(broadcast)
                else send(frame)
            },
            disconnect = {}
        )
        val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()
    }

    @Test
    fun multiplex_basic_flow() = runBlocking {
        TestServer.test(settings = {}) {
            val mux = TestServer.multiplex.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json
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
            assert(listOf("a", "b").contains(tEcho.channel))
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

    /**
     * Regression test: unsubscribe used to *add* the topic to the channel's set instead of removing it,
     * so a channel could never stop receiving a topic once subscribed, and the underlying subscription
     * was never released. A connection that had ever subscribed broadly kept that firehose for its
     * whole session.
     */
    @Test
    fun unsubscribe_actually_stops_delivery() = runBlocking {
        TestServer.test(settings = {}) {
            val mux = TestServer.multiplex.test()
            val json = contextOf<ServerRuntime>().externalSerialization.json
            var last: WebSocketFrame? = null
            mux.onMessageSent = { last = it }

            suspend fun send(message: MultiplexMessage) =
                mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), message)))

            send(MultiplexMessage(channel = "t", path = "/toggle", start = true))

            // While subscribed, a broadcast reaches the channel.
            TestServer.broadcast.send("first")
            val received = json.decodeFromString(MultiplexMessage.serializer(), (last as WebSocketFrame.Text).text)
            assertEquals("t", received.channel)
            assertEquals("first", received.data)

            // Ask the handler to unsubscribe, then broadcast again.
            send(MultiplexMessage(channel = "t", data = "off"))
            last = null
            TestServer.broadcast.send("second")

            assertNull(last, "Channel kept receiving the topic after unsubscribing")

            mux.close()
        }
    }
}
