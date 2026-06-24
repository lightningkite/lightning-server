package com.lightningkite.lightningserver.guide.samples

// region websockets-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.websockets.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
// endregion websockets-imports

// region echo-ws-server
object EchoWsServer : ServerBuilder() {

    // ws:// /echo — echoes every text frame back to the client with a prefix
    //
    // WebSocketHandler takes four lifecycle callbacks:
    //   willConnect  — called BEFORE the connection is established; returns STORAGE
    //   didConnect   — called AFTER the connection is established
    //   messageFromClient — called for each incoming frame from the client
    //   disconnect   — called when the connection closes
    //
    // STORAGE is the per-connection state. Here it is Unit — the echo handler
    // needs no per-connection data. For stateful handlers (e.g. tracking a username
    // or a room), use a data class.
    val echo = path.path("echo") bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        didConnect = {
            // Send a greeting frame as soon as the client connects.
            send("Echo server ready")
        },
        messageFromClient = { frame ->
            // `frame` is a WebSocketFrame — either Text or Binary.
            // WebSocketFrame.text is a convenience property that returns the string
            // content for text frames (hex for binary).
            send("Echo: ${frame.text}")
        },
        disconnect = { /* no cleanup needed */ }
    )
}
// endregion echo-ws-server

// region echo-ws-test
fun echoWsTest() = runBlocking {
    EchoWsServer.test(settings = {}) {
        // .test() on a WebSocketHandler returns a TestWebSocket.
        // The connection is fully established (willConnect + didConnect already ran).
        val received = mutableListOf<String>()

        val ws = EchoWsServer.echo.test()

        // Capture frames the server sends back via onMessageSent.
        ws.onMessageSent = { frame -> received.add(frame.text) }

        // The didConnect greeting arrives before test() returns, so it's already in
        // the received list if the handler sent it synchronously. In this case the
        // greeting was sent in didConnect which ran before test() returned.
        // We reset and only check the echo:
        received.clear()

        // Send a text frame to the server.
        ws.send(WebSocketFrame("hello"))

        // The server's messageFromClient ran synchronously; received now holds the reply.
        check(received.size == 1) { "Expected 1 reply, got ${received.size}" }
        check(received[0] == "Echo: hello") { "Unexpected reply: ${received[0]}" }

        ws.close()
    }
}
// endregion echo-ws-test

// region pubsub-ws-server
object BroadcastServer : ServerBuilder() {

    // A topic is a named pub/sub channel. Declare it on any PathSpec in your ServerBuilder.
    // Any number of WebSocket connections can subscribe to the same topic.
    // The server (or any HTTP endpoint) can publish to the topic to push messages to all subscribers.
    val announcementTopic = path.path("announce-topic").topic(String.serializer())

    // POST /announce — HTTP endpoint that publishes to the topic
    val announce = path.path("announce").post bind HttpHandler { request ->
        val message = request.body!!.text()
        // send() on a topic pushes to all subscribed WebSocket connections.
        announcementTopic.send(message)
        HttpResponse.plainText("Announced: $message")
    }

    // ws:// /listen — clients subscribe to the announcement topic and receive pushes
    val listen = path.path("listen") bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        didConnect = {
            // subscribe() registers this connection to receive messages from the topic.
            // The topicHandlers block below decides what to do when a message arrives.
            subscribe(announcementTopic)
        },
        topicHandlers = {
            // Bind a handler for each topic this connection subscribes to.
            // `message.value` is the typed payload published to the topic.
            announcementTopic bind { message ->
                send(message.value)
            }
        },
        disconnect = { /* unsubscription is automatic on close */ }
    )
}
// endregion pubsub-ws-server

// region pubsub-ws-test
fun broadcastWsTest() = runBlocking {
    BroadcastServer.test(settings = {}) {
        val received = mutableListOf<String>()

        // Connect two clients.
        val ws1 = BroadcastServer.listen.test()
        val ws2 = BroadcastServer.listen.test()
        ws1.onMessageSent = { received.add("ws1:${it.text}") }
        ws2.onMessageSent = { received.add("ws2:${it.text}") }

        // Send via the HTTP endpoint. sendWebSocketSubscriptionMessage is dispatched
        // synchronously in the test runtime, so both connections receive the frame
        // before the next line executes.
        BroadcastServer.announcementTopic.send("hello everyone")

        check(received.contains("ws1:hello everyone"))
        check(received.contains("ws2:hello everyone"))

        ws1.close()
        ws2.close()
    }
}
// endregion pubsub-ws-test

// @Test class so the sample functions are exercised by the test runner.
// Each test calls a sample function defined above so coverage is real.
class WebSocketSamplesTest {
    @Test
    fun echoWsRuns() = echoWsTest()

    @Test
    fun broadcastWsRuns() = broadcastWsTest()
}
