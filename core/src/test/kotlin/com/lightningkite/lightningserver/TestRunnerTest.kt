package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.definition.builder.topic
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.first
import com.lightningkite.lightningserver.runtime.TestRunner
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.runtime.set
import com.lightningkite.lightningserver.runtime.test
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

    object D1 : ServerBuilder() {

        val testEndpoint = path.path("test").get bind HttpHandler {
            HttpResponse.plainText("Hello world!")
        }
        val testEndpointWithArg = path.path("test").arg<String>("arg1").get bind HttpHandler {
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
        generalSettings set GeneralServerSettings()
    })

    @Test
    fun test() {
        D1.test(
            settings = {
                generalSettings set GeneralServerSettings()
            }
        ) {
            runBlocking {
                val response = testEndpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello world!", response.body!!.text())
            }
            runBlocking {
                val response = testEndpointWithArg.test("Todd")
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello, Todd!", response.body!!.text())
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