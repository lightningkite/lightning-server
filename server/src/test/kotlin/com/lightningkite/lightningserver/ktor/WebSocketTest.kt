package com.lightningkite.lightningserver.ktor

import com.lightningkite.lightningdb.ClientModule
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.auth.AuthSettings
import com.lightningkite.lightningserver.auth.ConfigureAuthKtTest
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.database
import com.lightningkite.lightningserver.pubsub.PubSubSettings
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.websocket.websocket
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import com.lightningkite.lightningserver.websocket.WebSockets as MyWebSockets

@Suppress("OPT_IN_USAGE")
class WebSocketTest {

    data class Settings(
        val generalServer: GeneralServerSettings = GeneralServerSettings(),
        val cache: CacheSettings = CacheSettings(),
        val pubSub: PubSubSettings = PubSubSettings(),
    )

    @Test
    fun socketTest() {
        var settings: Settings? = null
        SetOnce.allowOverwrite {
            settings = Settings()
        }
        var socketId: String? = null
        val socket = ServerPath("socket-test").websocket(
            connect = {
                println("connect $it")
                socketId = it.id
                GlobalScope.launch {
                    delay(200L)
                    MyWebSockets.send(socketId!!, "Test")
                }
            },
            message = { println("message $it") },
            disconnect = { println("disconnect $it") },
        )
        testApplication {
            application {
                settings
                lightningServer()
            }
            val client = createClient {
                install(WebSockets)
                install(ContentNegotiation) {
                    json(Json {
                        serializersModule = ClientModule
                    })
                }
            }
            client.webSocket(socket.toString()) {
                delay(100L)
                send("Hello world!")
                delay(150L)
                incoming.tryReceive().getOrNull()?.let { it as? Frame.Text }?.let { println(it.readText()) }
                delay(100L)
            }
        }
    }
}