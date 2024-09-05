package com.lightningkite.lightningserver.ktor

import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.pubsub.PubSubSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.websocket.MultiplexMessage
import com.lightningkite.lightningserver.websocket.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import com.lightningkite.lightningserver.websocket.websocket
import com.lightningkite.serialization.ClientModule
import com.lightningkite.uuid
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.lightningserver.websocket.WebSockets as MyWebSockets

class KtorKtTest {

    @Test
    fun socketTest() {
        TestSettings
        var socketId: WebSocketIdentifier? = null
        val socket = ServerPath("socket-test").websocket(
            connect = {
                println("connect $it")
                socketId = it.id
                GlobalScope.launch {
                    delay(200L)
                    socketId!!.send("Test")
                }
            },
            message = { println("message $it") },
            disconnect = { println("disconnect $it") },
        ).get.handler { HttpResponse.plainText("no")}
        testApplication {
            environment { watchPaths = listOf() }
            application {
                TestSettings
                lightningServer(LocalPubSub, LocalCache)
            }
            val client = createClient {
                install(WebSockets)
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            client.webSocket("socket-test") {
                delay(100L)
                send("Hello world!")
                delay(150L)
                incoming.tryReceive().getOrNull()?.let { it as? Frame.Text }?.let { println(it.readText()) }
                delay(100L)
            }
        }
    }

    @Test
    fun socketPathTest() {
        TestSettings
        var socketId: WebSocketIdentifier? = null
        val socket = ServerPath("socket-test").websocket(
            connect = {
                println("connect $it")
                socketId = it.id
                GlobalScope.launch {
                    delay(200L)
                    socketId!!.send("Test")
                }
            },
            message = { println("message $it") },
            disconnect = { println("disconnect $it") },
        )
        testApplication {
            environment { watchPaths = listOf() }
            application {
                TestSettings
                lightningServer(LocalPubSub, LocalCache)
            }
            val client = createClient {
                install(WebSockets)
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            client.webSocket("?path=/socket-test") {
                delay(100L)
                send("Hello world!")
                delay(150L)
                incoming.tryReceive().getOrNull()?.let { it as? Frame.Text }?.let { println(it.readText()) }
                delay(100L)
            }
        }
    }

    @Test
    fun multiplexSocketTest() {
        TestSettings
        var socketId: WebSocketIdentifier? = null
        val multiplexSocket = ServerPath("multiplex").websocket(MultiplexWebSocketHandler { LocalCache })
        val socket = ServerPath("socket-test").websocket(
            connect = {
                println("connect $it")
                socketId = it.id
                GlobalScope.launch {
                    delay(200L)
                    socketId!!.send("Test")
                }
            },
            message = { println("message $it") },
            disconnect = { println("disconnect $it") },
        )
        testApplication {
            environment { watchPaths = listOf() }
            application {
                TestSettings
                lightningServer(LocalPubSub, LocalCache)
            }
            val client = createClient {
                install(WebSockets)
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            val channel = uuid().toString()
            client.webSocket(multiplexSocket.toString()) {
                send(
                    Serialization.json.encodeToString(
                        MultiplexMessage(
                            channel = channel,
                            path = "socket-test",
                            start = true
                        )
                    )
                )
                delay(100L)
                send(
                    Serialization.json.encodeToString(
                        MultiplexMessage(
                            channel = channel,
                            path = "socket-test",
                            data = "Hello world!"
                        )
                    )
                )
                delay(150L)
                incoming.tryReceive().getOrNull()?.let { it as? Frame.Text }?.let { println(it.readText()) }
                delay(100L)
            }
        }
    }
}