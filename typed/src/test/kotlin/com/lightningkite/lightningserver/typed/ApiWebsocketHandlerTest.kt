package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.text
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiWebSocketHandlerTest {

    @Test
    fun updateStateImmediatelyWorks() {
        val TestServer = object : ServerBuilder() {
            val ws = path.path("testws") bind ApiWebSocketHandler<PathSpec0, Int, Nothing?, Int, Int>(
                summary = "running sum",
                auth = noAuth,
                willConnectType = { 0 },
                messageFromClientType = {
                    send(updateStateImmediately { v -> it + v })
                },
            )

            init {
                registerBasicMediaTypeCoders()
            }
        }
        TestServer.test(settings = {}) {
            runBlocking {
                val socket = ws.test()
                var received = -1
                socket.onMessageSent = {
                    received = it.text.trim('"').toInt()
                }
                var count = 0
                repeat(5) {
                    count++
                    socket.send(WebSocketFrame("1"))
                    assertEquals(count, received)
                }
            }
        }
    }

    @Test
    fun queueStateUpdatesWorks() {
        val TestServer = object : ServerBuilder() {
            val ws = path.path("testws") bind ApiWebSocketHandler<PathSpec0, Int, Nothing?, Int, Int>(
                summary = "running sum",
                auth = noAuth,
                willConnectType = { 0 },
                messageFromClientType = {
                    queueStateUpdate { v -> it + v }
                    send(currentState)
                },
            )

            init {
                registerBasicMediaTypeCoders()
            }
        }
        TestServer.test(settings = {}) {
            runBlocking {
                val socket = ws.test()
                var received = -1
                socket.onMessageSent = {
                    received = it.text.trim('"').toInt()
                }
                var count = 0
                repeat(5) {
                    socket.send(WebSocketFrame("1"))
                    assertEquals(count, received)
                    count++
                }
            }
        }
    }
}