package com.lightningkite.lightningserver

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    object D1 : ServerDefinition() {
        override val externalSerialization: Serialization = Serialization()
        override val internalSerialization: Serialization = Serialization()

        val testEndpoint = path.resolve("test").get bind httpHandler {
            HttpResponse.plainText("Hello world!")
        }
        val testEndpointWithArg = path.resolve("test").arg<String>("arg1").get bind httpHandler {
            HttpResponse.plainText("Hello, ${it.first}!")
        }
        val testWebsocketTopic: WebSocketTopic<PathSpec0, String> = path.resolve("broadcast").topic(String.serializer())
        val testWebsocket = path.resolve("mirror") bind webSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { subscribe(testWebsocketTopic) },
            topicHandlers = {
                testWebsocketTopic bind {
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
                socket.close()
            }
        }
    }
}