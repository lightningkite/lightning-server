# WebSockets

WebSocket endpoints in Lightning Server use the same path-builder syntax as
HTTP endpoints.  The framework manages the connection lifecycle, subscription
routing, and per-connection state; your code supplies four lifecycle callbacks.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#websockets-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.websockets.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.serializer
```

`com.lightningkite.lightningserver.runtime.*` is required for
`WebSocketTopic.send(value)` and related runtime extensions.
`com.lightningkite.lightningserver.websockets.*` brings in `WebSocketHandler`,
`WebSocketFrame`, `WebSocketConnection`, and the `send(String)` / `send(ByteArray)`
convenience extensions on connections.

## Defining a WebSocket Endpoint

Use `path.… bind WebSocketHandler(…)` to register a WebSocket handler on a
path.  `WebSocketHandler` is a factory function that accepts four named
callbacks:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#echo-ws-server -->
```kotlin
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
```

The four callbacks and their contexts:

| Callback | Context receiver | Called when |
|---|---|---|
| `willConnect` | `ServerRuntime` | Client initiates a connection (before it is accepted) |
| `didConnect` | `WebSocketConnection<PATH, STORAGE>` | Connection is established |
| `messageFromClient` | `WebSocketConnection<PATH, STORAGE>` | Client sends a frame |
| `disconnect` | `WebSocketConnection<PATH, STORAGE>` | Client disconnects or `close()` is called |

`willConnect` returns the initial **STORAGE** value.  The STORAGE type is
the per-connection state — a data class, a session ID string, a database row,
anything you need.  It is accessible as `currentState` inside `didConnect`,
`messageFromClient`, and `disconnect`.

Inside any callback that receives `WebSocketConnection` as context:

- `send(frame)` / `send(text: String)` / `send(bytes: ByteArray)` — send a
  frame to this client.
- `close(reason)` — close the connection programmatically.
- `currentState` — the current STORAGE value.
- `updateStateImmediately { old -> new }` — atomically update the state.

## Testing a WebSocket Endpoint

`WebSocketHandler.test()` is available inside a `testBlocking {}` block.  It runs
`willConnect` and `didConnect` synchronously, returns a `TestWebSocket`, and
gives you `send()` and `onMessageSent` to drive the conversation:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#echo-ws-test -->
```kotlin
fun echoWsTest() = EchoWsServer.testBlocking(settings = {}) {
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
```

The `TestWebSocket` API:

- `ws.send(frame)` — deliver a frame to the server's `messageFromClient`
- `ws.onMessageSent = { frame -> … }` — callback invoked when the server calls
  `send()` on the connection
- `ws.close()` — trigger `disconnect` and clean up subscriptions
- `ws.currentState` — the current STORAGE value for this connection

In the test runtime, all callbacks (including `messageFromClient`) execute
synchronously before `ws.send()` returns.  There is no async delivery; checking
`received` immediately after `ws.send(…)` is safe.

## Topics: Server-to-Client Push

A **topic** is a named pub/sub channel.  Declare it on any path, then
subscribe connections to it in `didConnect`.  Any code with a `ServerRuntime`
in context — an HTTP endpoint, a scheduled task, another WebSocket handler —
can publish to a topic to push messages to all subscribed connections:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#pubsub-ws-server -->
```kotlin
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
```

The test publishes directly to the topic (no HTTP round-trip needed) and
confirms both connections receive the frame:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#pubsub-ws-test -->
```kotlin
fun broadcastWsTest() = BroadcastServer.testBlocking(settings = {}) {
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
```

Topic key points:

- Topics are **typed**: `path.topic(String.serializer())` creates a
  `WebSocketTopic<PATH, String>`.  The `send(value)` and `bind { message ->
  message.value }` calls are type-safe.
- Topics are **scoped to paths**: the path determines the subscription key.
  Two topics at different paths are distinct even if they have the same message
  type.
- **Topics with path arguments** (e.g. `path.path("room").arg<String>("id")
  .topic(Message.serializer())`) let you scope subscriptions per-resource —
  each room ID is a separate channel.  The `subscribe(topic, path1)` extension
  passes the argument; `topic.send(path1, value)` targets a specific channel.

## Connection State (STORAGE)

`willConnect` returns the initial STORAGE value.  Use it to capture
anything the connection needs for the lifetime of the session — a session ID,
an authenticated user ID, a room name extracted from query parameters:

```kotlin
// Illustrative.
val chat = path.path("chat") bind WebSocketHandler(
    storageSerializer = String.serializer(),  // STORAGE is a username String
    willConnect = { request ->
        // request is WebSocketConnectRequest; read query params, headers, etc.
        request.queryParameters["username"] ?: "anonymous"
    },
    didConnect = {
        // currentState is the username returned by willConnect
        send("Welcome, $currentState!")
    },
    messageFromClient = { frame ->
        chatTopic.send("$currentState: ${frame.text}")
    },
    disconnect = {}
)
```

`updateStateImmediately { old -> new }` atomically replaces the state.
`queueStateUpdate { old -> new }` schedules an update to run after the current
callback completes — useful for high-frequency writes where strict ordering
within a single callback isn't required.

## WebSocket Paths with Arguments

WebSocket handlers support path arguments using the same syntax as HTTP
endpoints:

```kotlin
// Illustrative.
// ws:// /rooms/{roomId}
val room = path.path("rooms").arg<String>("roomId") bind WebSocketHandler(
    storageSerializer = String.serializer(),
    willConnect = { request ->
        // Access the first path argument via request.path.arg1
        request.path.arg1
    },
    didConnect = {
        // currentState is the roomId
        subscribe(roomTopic, currentState)
    },
    // …
)
```

The `.test()` extension for handlers with path arguments takes the argument as
its first parameter:

```kotlin
// Illustrative.
val ws = MyServer.room.test("lobby")  // path1 = "lobby"
```

> The code above is illustrative and verified against the `WebSocketHandler`
> builder signature in `core/src/main/kotlin/.../websockets/WebSocketHandler.kt`
> and `TestRunner.ext.kt`.  Path-argument WebSocket examples cannot be added
> as drift-checked regions here because the sample `ServerBuilder` objects for
> this chapter do not declare rooms — the pattern is identical to the echo and
> broadcast examples above.
