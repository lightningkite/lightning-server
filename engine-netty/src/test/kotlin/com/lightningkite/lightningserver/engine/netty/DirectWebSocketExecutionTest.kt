package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.flow.Flow
import okhttp3.*
import okio.ByteString
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.*
import kotlin.test.*

/**
 * Tests for DirectExecutableWebSocketHandler optimization in NettyEngine.
 *
 * These tests verify that CoroutineWebsocketHandler works correctly with:
 * 1. Direct execution (default) - bypasses pub/sub
 * 2. Pub/sub mode (forceWebSocketPubSub=true) - uses standard pub/sub path
 */
class DirectWebSocketExecutionTest {

    object TestServer : ServerBuilder() {
        val pubsub = setting("pubSub", PubSub.Settings())

        // Echo handler using CoroutineWebsocketHandler
        val echoHandler = path.path("echo") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
            ) {
                waitForFullConnect()
                incoming.collect { frame ->
                    send(frame)
                }
            }
        }

        // Handler that sends greeting after connect
        val greetingHandler = path.path("greeting") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
            ) {
                waitForFullConnect()
                send(WebSocketFrame.Text("Hello from server!"))
                incoming.collect { /* consume */ }
            }
        }

        // Handler that sends multiple messages
        val multiMessageHandler = path.path("multi") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
            ) {
                waitForFullConnect()
                send(WebSocketFrame.Text("One"))
                send(WebSocketFrame.Text("Two"))
                send(WebSocketFrame.Text("Three"))
                incoming.collect { /* consume */ }
            }
        }
    }

    private lateinit var engine: NettyEngine
    private var port: Int = 0
    private lateinit var client: OkHttpClient

    private fun startEngine(forcePubSub: Boolean) {
        // Pick a free port
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = NettyEngine(TestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.telemetrySettings.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            com.lightningkite.lightningserver.engine.local.enginePubSub.useDefault()
            com.lightningkite.lightningserver.engine.local.engineCache.useDefault()
            TestServer.pubsub.useDefault()  // Set the handler's pubsub setting
            forceWebSocketPubSub set forcePubSub
            nettyRunConfig set NettyRuntimeSettings(host = "127.0.0.1", port = port)
        }
        // Don't call readyUsingDefaults() - let start() call ready() with all settings
        // Start in background thread
        Thread {
            engine.start()
        }.start()

        // Wait for server to be ready by polling boundAddress
        var tries = 0
        while (engine.boundAddress == null && tries < 50) {
            Thread.sleep(100)
            tries++
        }
        if (engine.boundAddress == null) {
            throw IllegalStateException("NettyEngine failed to start within 5 seconds")
        }
        // Use actual bound port in case it changed
        port = engine.boundAddress!!.port

        client = OkHttpClient()
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.shutdown()
        }
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
        }
    }

    // ============= DIRECT EXECUTION TESTS (default) =============

    @Test
    fun direct_execution_echo_works() {
        startEngine(forcePubSub = false)

        val openLatch = CountDownLatch(1)
        val messageFuture = CompletableFuture<String>()

        val request = Request.Builder().url("ws://127.0.0.1:$port/echo").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messageFuture.complete(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messageFuture.completeExceptionally(t)
                openLatch.countDown()
            }
        })

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket failed to open")
        assertTrue(ws.send("Hello Direct!"))

        val response = messageFuture.get(5, TimeUnit.SECONDS)
        assertEquals("Hello Direct!", response)
        ws.close(1000, "done")
    }

    @Test
    fun direct_execution_binary_echo_works() {
        startEngine(forcePubSub = false)

        val openLatch = CountDownLatch(1)
        val messageFuture = CompletableFuture<ByteArray>()

        val request = Request.Builder().url("ws://127.0.0.1:$port/echo").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messageFuture.complete(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messageFuture.completeExceptionally(t)
                openLatch.countDown()
            }
        })

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket failed to open")
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(ws.send(ByteString.of(*data)))

        val response = messageFuture.get(5, TimeUnit.SECONDS)
        assertTrue(data.contentEquals(response))
        ws.close(1000, "done")
    }

    @Test
    fun direct_execution_greeting_received() {
        startEngine(forcePubSub = false)

        val messageFuture = CompletableFuture<String>()

        val request = Request.Builder().url("ws://127.0.0.1:$port/greeting").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messageFuture.complete(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messageFuture.completeExceptionally(t)
            }
        })

        val response = messageFuture.get(5, TimeUnit.SECONDS)
        assertEquals("Hello from server!", response)
        ws.close(1000, "done")
    }

    @Test
    fun direct_execution_multiple_messages() {
        startEngine(forcePubSub = false)

        val messages = mutableListOf<String>()
        val allReceived = CountDownLatch(3)

        val request = Request.Builder().url("ws://127.0.0.1:$port/multi").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(messages) {
                    messages.add(text)
                }
                allReceived.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                repeat(3) { allReceived.countDown() }
            }
        })

        assertTrue(allReceived.await(5, TimeUnit.SECONDS), "Did not receive all messages")
        assertEquals(listOf("One", "Two", "Three"), messages)
        ws.close(1000, "done")
    }

    @Test
    fun direct_execution_multiple_echo_round_trips() {
        startEngine(forcePubSub = false)

        val openLatch = CountDownLatch(1)
        val messages = mutableListOf<String>()
        val allReceived = CountDownLatch(5)

        val request = Request.Builder().url("ws://127.0.0.1:$port/echo").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(messages) {
                    messages.add(text)
                }
                allReceived.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                openLatch.countDown()
                repeat(5) { allReceived.countDown() }
            }
        })

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket failed to open")

        repeat(5) { i ->
            assertTrue(ws.send("Message $i"))
        }

        assertTrue(allReceived.await(5, TimeUnit.SECONDS), "Did not receive all messages")
        assertEquals((0..4).map { "Message $it" }, messages)
        ws.close(1000, "done")
    }

    // ============= PUB/SUB MODE TESTS (forceWebSocketPubSub=true) =============

    @Test
    @Ignore  // TODO: restore these tests when we can figure out how to smooth out their conflicts in same JVM
    fun pubsub_mode_echo_works() {
        startEngine(forcePubSub = true)

        val openLatch = CountDownLatch(1)
        val messageFuture = CompletableFuture<String>()

        val request = Request.Builder().url("ws://127.0.0.1:$port/echo").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messageFuture.complete(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messageFuture.completeExceptionally(t)
                openLatch.countDown()
            }
        })

        assertTrue(openLatch.await(10, TimeUnit.SECONDS), "WebSocket failed to open")
        assertTrue(ws.send("Hello PubSub!"))

        val response = messageFuture.get(10, TimeUnit.SECONDS)
        assertEquals("Hello PubSub!", response)
        ws.close(1000, "done")
    }

    @Test
    @Ignore  // TODO: restore these tests when we can figure out how to smooth out their conflicts in same JVM
    fun pubsub_mode_greeting_received() {
        startEngine(forcePubSub = true)

        val messageFuture = CompletableFuture<String>()

        val request = Request.Builder().url("ws://127.0.0.1:$port/greeting").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messageFuture.complete(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messageFuture.completeExceptionally(t)
            }
        })

        val response = messageFuture.get(10, TimeUnit.SECONDS)
        assertEquals("Hello from server!", response)
        ws.close(1000, "done")
    }
}
