package com.lightningkite.lightningserver.ktor

import com.lightningkite.lightningdb.ClientModule
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.pubsub.PubSubSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.websocket.websocket
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.Test
import com.lightningkite.lightningserver.websocket.WebSockets as MyWebSockets

//@Suppress("OPT_IN_USAGE")
//class WebSocketTest {
//
//    @Test
//    fun socketTest() {
//        TestSettings
//        var socketId: String? = null
//        val socket = ServerPath("socket-test").websocket(
//            connect = {
//                println("connect $it")
//                socketId = it.id
//                GlobalScope.launch {
//                    delay(200L)
//                    MyWebSockets.send(socketId!!, "Test")
//                }
//            },
//            message = { println("message $it") },
//            disconnect = { println("disconnect $it") },
//        )
//        testApplication {
//            environment { watchPaths = listOf() }
//            application {
//                TestSettings
//                lightningServer(LocalPubSub, LocalCache)
//            }
//            val client = createClient {
//                install(WebSockets)
//                install(ContentNegotiation) {
//                    json(Json {
//                        serializersModule = ClientModule
//                    })
//                }
//            }
//            client.webSocket("socket-test") {
//                delay(100L)
//                send("Hello world!")
//                delay(150L)
//                incoming.tryReceive().getOrNull()?.let { it as? Frame.Text }?.let { println(it.readText()) }
//                delay(100L)
//            }
//        }
//    }
//}