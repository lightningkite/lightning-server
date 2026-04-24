package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    object TestServer : ServerBuilder() {
        val sampleSetting = setting("sample", "default")
        val testEndpoint = path.path("test").get bind HttpHandler {
            val settingValue = sampleSetting()
            HttpResponse.plainText("Hello world!")
        }
        val testEndpointWithArg = path.path("test").arg<String>("arg1").get bind HttpHandler {
            HttpResponse.plainText("Hello, ${it.arg1}!")
        }
        val testWebsocketTopic = path.path("broadcast").topic(String.serializer())

        val testWebsocket = path.path("mirror") bind WebSocketHandler(
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

        val modelEndpoints = path.path("model") include TestModelEndpoints
    }

    object TestModelEndpoints : ServerBuilder() {
        val describePerson = path.path("describe").arg<String>("id").get bind HttpHandler {
            if (it.arg1 == "hunter") HttpResponse.plainText("Really cool imo")
            else HttpResponse(status = HttpStatus.NotFound)
        }

        val modelWebsocket = path.path("mirror") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { subscribe(TestServer.testWebsocketTopic) },
            topicHandlers = {
                TestServer.testWebsocketTopic bind {
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

    @Test
    fun test() {
        TestServer.test(
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

    @Test
    fun testModules() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = modelEndpoints.describePerson.test("1234")
                assertEquals(HttpStatus.NotFound, response.status)
            }
            runBlocking {
                val response = modelEndpoints.describePerson.test("hunter")
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Really cool imo", response.body!!.text())
            }
            runBlocking {
                val rootSocket = testWebsocket.test()
                val modelSocket = modelEndpoints.modelWebsocket.test()
                var lastRootMessage: WebSocketFrame? = null
                var lastModelMessage: WebSocketFrame? = null
                rootSocket.onMessageSent = {
                    lastRootMessage = it
                }
                modelSocket.onMessageSent = {
                    lastModelMessage = it
                }
                rootSocket.send(WebSocketFrame("Ping!"))
                assertEquals(WebSocketFrame("Ping!"), lastRootMessage)
                modelSocket.send(WebSocketFrame("Ping!"))
                assertEquals(WebSocketFrame("Ping!"), lastModelMessage)
                testWebsocketTopic.send("Pong.")
                assertEquals(WebSocketFrame("Pong."), lastRootMessage)
                assertEquals(WebSocketFrame("Pong."), lastModelMessage)
                rootSocket.close()
            }
        }
    }
}