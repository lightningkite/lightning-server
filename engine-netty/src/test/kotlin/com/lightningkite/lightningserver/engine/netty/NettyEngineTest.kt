package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.serialization.builtins.serializer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class NettyEngineTest {

    object TestServer : ServerBuilder() {
        // Simple HTTP endpoint
        val ping = path.path("ping").get bind HttpHandler {
            HttpResponse.plainText("pong")
        }

        // Simple WebSocket echo
        val echo = path.path("echo") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            messageFromClient = { frame ->
                // Echo back whatever we get
                send(frame)
            },
        )
    }

    private lateinit var engine: NettyEngine
    private var port: Int = 0

    @BeforeTest
    fun setUp() {
        // Pick a free port
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = NettyEngine(TestServer.build())
        engine.settings.run {
            // Provide defaults for all required settings so NettyEngine.start() can mark ready
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.telemetrySettings.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            com.lightningkite.lightningserver.engine.local.enginePubSub.useDefault()
            com.lightningkite.lightningserver.engine.local.engineCache.useDefault()
            nettyRunConfig set NettyRuntimeSettings(host = "127.0.0.1", port = port)
        }
        engine.start()
    }

    @AfterTest
    fun tearDown() {
        engine.shutdown()
    }

//    @Test
//    fun http_ping_responds_ok() {
//        val client = HttpClient.newHttpClient()
//        val actualPort = engine.boundAddress?.port ?: port
//        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$actualPort/ping"))
//            .GET()
//            .build()
//        val res = client.send(req, BodyHandlers.ofString())
//        assertEquals(200, res.statusCode())
//        assertEquals("pong", res.body())
//        assertTrue(res.headers().firstValue("content-type").orElse("").lowercase().startsWith("text/plain"))
//    }
//
//    @Test
//    fun websocket_echo_text_round_trip() {
//        val httpClient = HttpClient.newHttpClient()
//        val actualPort = engine.boundAddress?.port ?: port
//        println("[DEBUG_LOG] Using port=$actualPort for WS test")
//        // Wait until HTTP endpoint is responsive to avoid race on server startup
//        run {
//            var tries = 0
//            while (tries < 20) {
//                try {
//                    val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ping")).GET().build()
//                    val res = httpClient.send(req, BodyHandlers.ofString())
//                    if (res.statusCode() == 200) break
//                } catch (_: Exception) {}
//                Thread.sleep(50)
//                tries++
//            }
//        }
//        val openLatch = CountDownLatch(1)
//        val msgFuture = CompletableFuture<String>()
//        val client = okhttp3.OkHttpClient()
//        val request = okhttp3.Request.Builder().url("ws://127.0.0.1:$actualPort/echo").build()
//        val ws = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
//            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
//                openLatch.countDown()
//            }
//            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
//                msgFuture.complete(text)
//            }
//            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
//                println("[DEBUG_LOG] WS onFailure: ${t.message} status=" + (response?.code ?: -1) + " body=" + (response?.body?.string() ?: ""))
//                msgFuture.completeExceptionally(t)
//                openLatch.countDown()
//            }
//        })
//
//        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket failed to open in time")
//        assertTrue(ws.send("hello"))
//        val echoed = msgFuture.get(5, TimeUnit.SECONDS)
//        assertEquals("hello", echoed)
//        ws.close(1000, "bye")
//        client.dispatcher.executorService.shutdown()
//    }
}
