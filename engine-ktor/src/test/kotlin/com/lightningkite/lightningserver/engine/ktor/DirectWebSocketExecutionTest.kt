package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.pubsub.PubSub
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for DirectExecutableWebSocketHandler optimization in KtorEngine.
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

    private fun createEngine(forcePubSub: Boolean): KtorEngine {
        val engine = KtorEngine(TestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.telemetrySettings.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            com.lightningkite.lightningserver.engine.local.enginePubSub.useDefault()
            com.lightningkite.lightningserver.engine.local.engineCache.useDefault()
            TestServer.pubsub.useDefault()  // Set the handler's pubsub setting
            forceWebSocketPubSub set forcePubSub
            ktorRunConfig set KtorRuntimeSettings(host = "127.0.0.1", port = 0)
        }
        engine.settings.readyUsingDefaults()
        return engine
    }

    private fun runWithEngine(forcePubSub: Boolean, block: suspend ApplicationTestBuilder.(KtorEngine) -> Unit) =
        testApplication {
            val engine = createEngine(forcePubSub)
            application {
                with(engine) { adapt() }
            }
            block(engine)
        }

    // ============= DIRECT EXECUTION TESTS (default) =============

    @Test
    fun direct_execution_echo_works() = runWithEngine(forcePubSub = false) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/echo?path=/echo") {
            // Send a message
            send(Frame.Text("Hello Direct!"))

            // Receive echo
            val response = withTimeout(5000.milliseconds) { incoming.receive() }
            assertTrue(response is Frame.Text)
            assertEquals("Hello Direct!", response.readText())
        }
    }

    @Test
    fun direct_execution_binary_echo_works() = runWithEngine(forcePubSub = false) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/echo?path=/echo") {
            // Send binary data
            val data = byteArrayOf(1, 2, 3, 4, 5)
            send(Frame.Binary(true, data))

            // Receive echo
            val response = withTimeout(5000.milliseconds) { incoming.receive() }
            assertTrue(response is Frame.Binary)
            assertTrue(data.contentEquals(response.data))
        }
    }

    @Test
    fun direct_execution_greeting_received() = runWithEngine(forcePubSub = false) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/greeting?path=/greeting") {
            // Should receive greeting
            val response = withTimeout(5000.milliseconds) { incoming.receive() }
            assertTrue(response is Frame.Text)
            assertEquals("Hello from server!", response.readText())
        }
    }

    @Test
    fun direct_execution_multiple_messages() = runWithEngine(forcePubSub = false) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/multi?path=/multi") {
            val messages = mutableListOf<String>()

            // Receive all messages
            repeat(3) {
                val response = withTimeout(5000.milliseconds) { incoming.receive() }
                if (response is Frame.Text) {
                    messages.add(response.readText())
                }
            }

            assertEquals(listOf("One", "Two", "Three"), messages)
        }
    }

    @Test
    fun direct_execution_multiple_echo_round_trips() = runWithEngine(forcePubSub = false) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/echo?path=/echo") {
            repeat(5) { i ->
                send(Frame.Text("Message $i"))
                val response = withTimeout(5000.milliseconds) { incoming.receive() }
                assertTrue(response is Frame.Text)
                assertEquals("Message $i", response.readText())
            }
        }
    }

    // ============= PUB/SUB MODE TESTS (forceWebSocketPubSub=true) =============

    @Test
    @Ignore  // TODO: restore these tests when we can figure out how to smooth out their conflicts in same JVM
    fun pubsub_mode_echo_works() = runWithEngine(forcePubSub = true) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/echo?path=/echo") {
            // Send a message
            send(Frame.Text("Hello PubSub!"))

            // Receive echo - may need longer timeout due to pub/sub latency
            val response = withTimeout(10000.milliseconds) { incoming.receive() }
            assertTrue(response is Frame.Text)
            assertEquals("Hello PubSub!", response.readText())
        }
    }

    @Test
    @Ignore  // TODO: restore these tests when we can figure out how to smooth out their conflicts in same JVM
    fun pubsub_mode_greeting_received() = runWithEngine(forcePubSub = true) { _ ->
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/greeting?path=/greeting") {
            // Should receive greeting - may need longer timeout due to pub/sub latency
            val response = withTimeout(10000.milliseconds) { incoming.receive() }
            assertTrue(response is Frame.Text)
            assertEquals("Hello from server!", response.readText())
        }
    }
}
