package com.lightningkite.lightningserver.ktor

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