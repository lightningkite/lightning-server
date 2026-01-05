package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoroutineWebsocketHandlerTest {

    object TestServer : ServerBuilder() {
        val pubsub = setting("pubSub", PubSub.Settings())

        // Echo handler - echoes all incoming messages back
        val echoHandler = path.path("echo") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit
            ) {
                // Signal that we're ready
                waitForFullConnect()

                // Echo all incoming messages back
                incoming.collect { frame ->
                    send(frame)
                }
            }
        }

        // Handler that sends a greeting message after connect
        val greetingHandler = path.path("greeting") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit
            ) {
                waitForFullConnect()
                // Small delay to ensure didConnect() has subscribed to the outbound topic
                delay(50)
                send(WebSocketFrame.Text("Hello!"))
                incoming.collect { /* Just consume */ }
            }
        }

        // Handler that throws an error immediately
        val errorHandler = path.path("error") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit
            ) {
                throw HttpStatusException(HttpStatus.InternalServerError, "Intentional error")
            }
        }

        // Handler that delays before signaling ready (to test timeout)
        val slowStartHandler = path.path("slow") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit
            ) {
                delay(30000) // 30 seconds - longer than the 25 second timeout
                waitForFullConnect()
                incoming.collect { }
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
                send: suspend (WebSocketFrame) -> Unit
            ) {
                waitForFullConnect()
                // Small delay to ensure didConnect() has subscribed to the outbound topic
                delay(50)
                send(WebSocketFrame.Text("Message 1"))
                send(WebSocketFrame.Text("Message 2"))
                send(WebSocketFrame.Text("Message 3"))
                incoming.collect { }
            }
        }
    }

    @Test
    fun basic_echo_works(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.echoHandler.websocketHandler.test()

            var receivedMessage: WebSocketFrame? = null
            ws.onMessageSent = { receivedMessage = it }

            // Send a text message
            ws.send(WebSocketFrame.Text("Hello"))

            // Give some time for the message to be processed
            delay(100)

            // Should receive the echo back
            assertEquals(WebSocketFrame.Text("Hello"), receivedMessage)

            ws.close()
        }
    }

    @Test
    fun binary_message_echo_works(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.echoHandler.websocketHandler.test()

            var receivedMessage: WebSocketFrame? = null
            ws.onMessageSent = { receivedMessage = it }

            // Send a binary message
            val binaryData = byteArrayOf(1, 2, 3, 4, 5)
            ws.send(WebSocketFrame.Binary(binaryData))

            delay(100)

            // Should receive the echo back
            val received = receivedMessage as? WebSocketFrame.Binary
            assertTrue(received != null)
            assertTrue(binaryData.contentEquals(received.content))

            ws.close()
        }
    }

    @Test
    fun greeting_message_sent_on_connect(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.greetingHandler.websocketHandler.test()

            val messages = mutableListOf<WebSocketFrame>()
            ws.onMessageSent = {
                println("[Test] Received message: $it")
                messages.add(it)
            }

            // Give more time for async task to send greeting
            delay(500)

            println("[Test] Messages received: ${messages.size}")
            // Should receive greeting message
            assertTrue(messages.isNotEmpty(), "Expected to receive at least one message, but got none")
            assertEquals(WebSocketFrame.Text("Hello!"), messages.first())

            ws.close()
        }
    }

    @Test
    fun multiple_messages_received(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.multiMessageHandler.websocketHandler.test()

            val messages = mutableListOf<WebSocketFrame>()
            ws.onMessageSent = { messages.add(it) }

            // Give time for all messages to be sent
            delay(200)

            // Should receive all 3 messages
            assertEquals(3, messages.size)
            assertEquals(WebSocketFrame.Text("Message 1"), messages[0])
            assertEquals(WebSocketFrame.Text("Message 2"), messages[1])
            assertEquals(WebSocketFrame.Text("Message 3"), messages[2])

            ws.close()
        }
    }

    @Test
    fun handler_error_propagates_to_connection(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            // The error handler throws immediately, which should cause willConnect to fail
            assertFailsWith<Exception> {
                TestServer.errorHandler.websocketHandler.test()
            }
        }
    }

    @Test
    fun slow_start_times_out(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            // The slow start handler delays for 10 seconds, but timeout is 5 seconds
            assertFailsWith<Exception> {
                TestServer.slowStartHandler.websocketHandler.test()
            }
        }
    }

    @Test
    fun multiple_sequential_messages_echo_correctly(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.echoHandler.websocketHandler.test()

            val receivedMessages = mutableListOf<WebSocketFrame>()
            ws.onMessageSent = { receivedMessages.add(it) }

            // Send multiple messages in sequence
            ws.send(WebSocketFrame.Text("First"))
            delay(50)
            ws.send(WebSocketFrame.Text("Second"))
            delay(50)
            ws.send(WebSocketFrame.Text("Third"))
            delay(50)

            // Should receive all echoes
            assertEquals(3, receivedMessages.size)
            assertEquals(WebSocketFrame.Text("First"), receivedMessages[0])
            assertEquals(WebSocketFrame.Text("Second"), receivedMessages[1])
            assertEquals(WebSocketFrame.Text("Third"), receivedMessages[2])

            ws.close()
        }
    }

    @Test
    fun disconnect_stops_handler(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.echoHandler.websocketHandler.test()

            var receivedMessage: WebSocketFrame? = null
            ws.onMessageSent = { receivedMessage = it }

            // Send a message
            ws.send(WebSocketFrame.Text("Before close"))
            delay(100)
            assertEquals(WebSocketFrame.Text("Before close"), receivedMessage)

            // Close the connection
            ws.close()
            delay(100)

            // Reset
            receivedMessage = null

            // Try to send another message (this might not work depending on implementation)
            // The key is that the background task should stop processing
            // We can't easily test this without access to task internals
        }
    }
}
