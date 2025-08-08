package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.httpHandler
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.subscribe
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.lightningserver.websockets.webSocketHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    object D1 : ServerDefinition() {
        override val externalSerialization: Serialization = Serialization()
        override val internalSerialization: Serialization = Serialization()

        val testEndpoint = path.path("test").get bind httpHandler {
            HttpResponse.plainText("Hello world!")
        }
        val testEndpointWithArg = path.path("test").arg<String>("arg1").get bind httpHandler {
            HttpResponse.plainText("Hello, ${it.first}!")
        }
        val testWebsocketTopic = (path.path("broadcast")).topic(String.serializer())
        val testWebsocket = path.path("mirror") bind webSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { subscribe(testWebsocketTopic) },
            topicHandlers = {
                testWebsocketTopic bind {
                    println("Topic hit!")
                    send(WebSocketFrame(it.value))
                }
            },
            messageFromClient = { frame ->
                if (frame is WebSocketFrame.Text && frame.text == "close") {
                    close(WebSocketClose.NORMAL)
                } else {
                    send(frame)  // Mirror
                }
            },
            disconnect = {}
        )
    }

    val test = TestRunner(D1, settings = {
        generalServerSettings set GeneralServerSettings()
    })

    @Test
    fun test() {
        D1.test(
            settings = {
                generalServerSettings set GeneralServerSettings()
            }
        ) {
            runBlocking {
                val response = testEndpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello world!", response.body!!.text)
            }
            runBlocking {
                val response = testEndpointWithArg.test("Todd")
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello, Todd!", response.body!!.text)
            }
            runBlocking {
                val socket = testWebsocket.test()
                var lastMessage: WebSocketFrame? = null
                socket.onMessageSent = {
                    lastMessage = it
                }
                socket.send(WebSocketFrame("Ping!"))
                assertEquals(WebSocketFrame("Ping!"), lastMessage)
                testWebsocketTopic.send("Pong.")
                assertEquals(WebSocketFrame("Pong."), lastMessage)
                socket.close()
            }
        }
    }
}