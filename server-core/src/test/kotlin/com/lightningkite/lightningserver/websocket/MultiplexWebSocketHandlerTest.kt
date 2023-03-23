package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningdb.MultiplexMessage
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class MultiplexWebSocketHandlerTest {
    @Test
    fun testSimultaneous() {
        val firstMirror = WebSocketsTest.TestMirrorSocket()
        val first = ServerPath.root.path("first").websocket(firstMirror)
        val secondMirror = WebSocketsTest.TestMirrorSocket()
        val second = ServerPath.root.path("second/{part}").websocket(secondMirror)
        val target = ServerPath.root.path("mp").websocket(MultiplexWebSocketHandler { LocalCache })
        runBlocking {
            target.test {
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "first", path = first.toString(), start = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("first", channel)
                    assertEquals(true, start)
                    assertEquals(null, error)
                    assertEquals(null, data)
                    assertEquals(false, end)
                }
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", path = second.toString(mapOf("part" to "test")), start = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertEquals(true, start)
                    assertEquals(null, error)
                    assertEquals(null, data)
                    assertEquals(false, end)
                }

                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "first", data = "Sending first")))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("first", channel)
                    assertEquals("Sending first", data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(false, end)
                }
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", data = "Sending second")))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertEquals("Sending second", data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(false, end)
                }

                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "first", end = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("first", channel)
                    assertEquals(null, data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(true, end)
                }
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", end = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertEquals(null, data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(true, end)
                }
            }
            firstMirror.assertCounts(1, 1, 1)
            secondMirror.assertCounts(1, 1, 1)
        }
    }

    @Test fun testMessageError() {
        val firstMirror = WebSocketsTest.TestMirrorSocket()
        val first = ServerPath.root.path("first").websocket(firstMirror)
        val secondMirror = object: WebSocketsTest.TestMirrorSocket() {
            override suspend fun message(event: WebSockets.MessageEvent) {
                super.message(event)
                throw Exception("oops")
            }
        }
        val second = ServerPath.root.path("second/{part}").websocket(secondMirror)
        val target = ServerPath.root.path("mp").websocket(MultiplexWebSocketHandler { LocalCache })
        runBlocking {
            target.test {
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "first", path = first.toString(), start = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("first", channel)
                    assertEquals(true, start)
                    assertEquals(null, error)
                    assertEquals(null, data)
                    assertEquals(false, end)
                }
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", path = second.toString(mapOf("part" to "test")), start = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertEquals(true, start)
                    assertEquals(null, error)
                    assertEquals(null, data)
                    assertEquals(false, end)
                }

                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "first", data = "Sending first")))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("first", channel)
                    assertEquals("Sending first", data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(false, end)
                }
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", data = "Sending second")))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertEquals("Sending second", data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(false, end)
                }
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertEquals(null, data)
                    assertEquals("oops", error)
                    assertEquals(false, start)
                    assertEquals(true, end)
                }

                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", data = "Sending second")))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("second", channel)
                    assertNotNull(error)
                    assertEquals(false, start)
                    assertEquals(false, end)
                }

                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "first", end = true)))
                Serialization.json.decodeFromString<MultiplexMessage>(this.incoming.receive()).apply {
                    assertEquals("first", channel)
                    assertEquals(null, data)
                    assertEquals(null, error)
                    assertEquals(false, start)
                    assertEquals(true, end)
                }
                this.send(Serialization.json.encodeToString(MultiplexMessage(channel = "second", end = true)))
            }
        }
        firstMirror.assertCounts(1, 1, 1)
        secondMirror.assertCounts(1, 1, 1)
    }
}

/*
Properties to test:
    An exception in any of the handler parts should cause a disconnect
        Multiplex only shuts down the channel
    The disconnect handler always runs eventually
        When server disconnects
        When client disconnects
        When sending a socket message fails
    Connect only ever runs once
    Disconnect only ever runs once
    Messages can never be emitted after a disconnect
    Server-side disconnect works
    Sending a message during the connect phase throws an error

    Multiplex specific
        No possible channel injection attacks
        No possible channel overlaps between clients
        Disconnecting a multiplex shuts down every channel individually
 */