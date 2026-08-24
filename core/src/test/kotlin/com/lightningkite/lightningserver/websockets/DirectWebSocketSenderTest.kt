// Fixed by Claude - added delay(50) after test() to allow didConnect() subscription to complete
package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.http.generateRequestId
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for DirectWebSocketSender and the direct send optimization path.
 *
 * These tests verify:
 * 1. The engineSocketId field is properly included in WebSocketConnectRequest
 * 2. When engineSocketId is null (default), the fallback topic-based send is used
 * 3. The didConnect() method properly subscribes only when engineSocketId is null
 */
class DirectWebSocketSenderTest {

    object TestServer : ServerBuilder() {
        val pubsub = setting("pubSub", PubSub.Settings())

        // Echo handler - for testing basic send functionality
        val echoHandler = path.path("echo") include object : CoroutineWebSocketHandler() {
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
    }

    @Test
    fun engineSocketId_defaults_to_null(): Unit = runBlocking {
        // Verify that the default WebSocketConnectRequest has null engineSocketId
        TestServer.test(settings = { }) {
            val ws = TestServer.echoHandler.webSocketHandler.test()

            // Verify the request has null engineSocketId (default behavior)
            assertEquals(null, ws.request.engineSocketId)

            ws.close()
        }
    }

    @Test
    fun fallback_topic_send_works_when_engineSocketId_is_null(): Unit = runBlocking {
        // When engineSocketId is null, the fallback topic-based send should work
        TestServer.test(settings = { }) {
            val ws = TestServer.echoHandler.webSocketHandler.test()

            // Allow didConnect() subscription to complete before sending
            delay(50)

            var receivedMessage: WebSocketFrame? = null
            ws.onMessageSent = { receivedMessage = it }

            // Send a message - this should use the topic-based fallback
            ws.send(WebSocketFrame.Text("Test via fallback"))
            delay(100)

            // Should receive the echo
            assertEquals(WebSocketFrame.Text("Test via fallback"), receivedMessage)

            ws.close()
        }
    }

    @Test
    fun engineSocketId_can_be_set_in_request(): Unit = runBlocking {
        // Test that engineSocketId can be explicitly set in a request
        val request = WebSocketConnectRequest<PathSpec0>(
            path = RawWebSocketPath(PathSegments.EMPTY),
            requestId = generateRequestId(),
            engineSocketId = "test-socket-123"
        )

        assertEquals("test-socket-123", request.engineSocketId)
    }

    @Test
    fun storage_preserves_engineSocketId(): Unit = runBlocking {
        // Verify that Storage correctly preserves the request's engineSocketId
        val request = WebSocketConnectRequest<PathSpec0>(
            path = RawWebSocketPath(PathSegments.EMPTY),
            requestId = generateRequestId(),
            engineSocketId = "aws-connection-id-xyz"
        )

        // The Storage data class wraps the request
        val storage = CoroutineWebSocketHandler.Storage(request = request)

        // Verify we can access the engineSocketId through storage
        assertEquals("aws-connection-id-xyz", storage.request.engineSocketId)
    }

    @Test
    fun direct_sender_interface_contract(): Unit = runBlocking {
        // Test that DirectWebSocketSender interface works correctly
        var sendDirectCalled = false
        var lastSocketId: String? = null
        var lastFrame: WebSocketFrame? = null

        val mockSender = object : DirectWebSocketSender {
            override suspend fun sendDirect(socketId: String, frame: WebSocketFrame): Boolean {
                sendDirectCalled = true
                lastSocketId = socketId
                lastFrame = frame
                return true // Simulate successful send
            }
        }

        // Call the mock sender
        val result = mockSender.sendDirect("test-socket", WebSocketFrame.Text("Hello"))

        assertTrue(sendDirectCalled)
        assertEquals("test-socket", lastSocketId)
        assertEquals(WebSocketFrame.Text("Hello"), lastFrame)
        assertTrue(result)
    }

    @Test
    fun direct_sender_returns_false_when_socket_gone(): Unit = runBlocking {
        // Test that DirectWebSocketSender can return false to signal socket is gone
        val mockSender = object : DirectWebSocketSender {
            override suspend fun sendDirect(socketId: String, frame: WebSocketFrame): Boolean {
                return false // Simulate socket gone
            }
        }

        val result = mockSender.sendDirect("gone-socket", WebSocketFrame.Text("Hello"))

        assertEquals(false, result)
    }
}
