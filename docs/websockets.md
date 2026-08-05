# WebSockets

Last updated July 2025 (`version-5`)

Lightning Server supports WebSockets for real-time, two-way communication. The core primitives live in
`com.lightningkite.lightningserver.websockets`; the `typed` module adds type-safe WebSockets that participate in
SDK generation.

A key design goal is that the same handler runs identically whether the server is a single local process or a
fleet of AWS Lambda instances behind API Gateway. To make that work, connection state and cross-connection
delivery are modeled explicitly (serializable storage and pub/sub topics) rather than kept in local memory.

## Declaring a WebSocket endpoint

Bind a `WebSocketHandler` to a path with `bind`. The handler is built from lifecycle callbacks. The value returned
by `willConnect` becomes this connection's `STORAGE` (its per-connection state); its type is inferred.

```kotlin
import com.lightningkite.lightningserver.websockets.*
import kotlin.uuid.Uuid

object ChatEndpoints : ServerBuilder() {
    val echo = path.path("ws").path("echo") bind WebSocketHandler(
        // Runs before the connection is accepted. Returns the initial STORAGE.
        willConnect = { request -> "echo-${Uuid.random()}" },
        // Runs once the connection is open. `this` is the connection.
        didConnect = { send("Echo server ready") },
        // Runs for each inbound frame from the client.
        messageFromClient = { frame -> send("Echo: ${frame.text}") },
        // Runs when the connection closes.
        disconnect = { reason -> println("Closed: $currentState ($reason)") },
    )
}
```

### Connection lifecycle

The callbacks receive different contexts:

- `willConnect: suspend ServerRuntime.(request) -> STORAGE` — runs with a `ServerRuntime`. Return the initial
  state. Throwing here rejects the connection.
- `didConnect`, `messageFromClient`, `disconnect` — run with the `WebSocketConnection` as receiver, which extends
  `ServerRuntime` and adds WebSocket-specific members.

Inside those callbacks you have access to:

- `currentState: STORAGE` — the current per-connection state.
- `send(...)` — send a frame to the client. Overloads accept `String`, `ByteArray`, or a `WebSocketFrame`.
- `subscribe(topic)` / `unsubscribe(topic)` — join or leave a pub/sub topic.
- `updateStateImmediately { }` / `queueStateUpdate { }` — atomically update `STORAGE`.
- `close(reason)` — close with a `WebSocketClose` code (e.g. `WebSocketClose.NORMAL`).

Inbound frames are `WebSocketFrame` (`WebSocketFrame.Text` or `WebSocketFrame.Binary`). Use `frame.text` for the
string form.

## Topics and pub/sub delivery

Because a connection may live on a different instance than the code that wants to message it, server-to-client
broadcasts go through **topics**. A topic is a named, typed channel that connections subscribe to; publishing to it
delivers the message to every subscribed connection, across all instances, via the configured pub/sub service.

Declare a topic on a path with `.topic(serializer)`:

```kotlin
import com.lightningkite.lightningserver.runtime.send // topic.send(...) extension
import kotlinx.serialization.builtins.serializer

object ChatEndpoints : ServerBuilder() {
    val chatTopic = path.path("ws").path("chat-topic").topic(ChatMessage.serializer())

    val chatSocket = path.path("ws").path("chat") bind WebSocketHandler(
        willConnect = { Uuid.random().toString() },
        didConnect = {
            subscribe(chatTopic)                 // start receiving topic messages
            send("Welcome, session $currentState")
        },
        messageFromClient = { frame ->
            val incoming = Json.decodeFromString(ChatMessage.serializer(), frame.text)
            chatTopic.send(incoming)             // broadcast to all subscribers
        },
        // Handle messages that arrive from subscribed topics.
        topicHandlers = {
            chatTopic bind { message ->
                send(Json.encodeToString(ChatMessage.serializer(), message.value))
            }
        },
    )
}
```

You can also publish to a topic from anywhere that has a `ServerRuntime` context — for example, from an HTTP
handler — using the `send` extension (`import com.lightningkite.lightningserver.runtime.send`):

```kotlin
val announce = path.path("announce").arg<String>("text").get bind HttpHandler { request ->
    chatTopic.send(ChatMessage(content = request.path.arg1))
    HttpResponse.plainText("sent")
}
```

Topics may carry path parameters, so subscriptions can be scoped to a specific resource. A single-argument topic is
published with `topic.send(path1 = "user123", value = ...)` and subscribed to with `subscribe(topic, "user123")`.

## Multiplexed connections

`MultiplexWebSocketHandler` lets a client run many logical channels over a single physical WebSocket connection,
which is useful when a client wants several simultaneous subscriptions without opening many sockets.

```kotlin
val multiplex = path.path("ws").path("multiplex") bind MultiplexWebSocketHandler()
```

`QueryParamWebSocketHandler` is a related helper that selects the underlying handler based on a query parameter.

## Typed WebSockets

The `typed` module provides `ApiWebsocketHandler`, which adds typed `INPUT`/`OUTPUT` messages, authentication, and
SDK generation. Instead of raw frames, your callbacks receive already-deserialized `INPUT` values and `send` takes
an `OUTPUT` value; content negotiation (JSON/CBOR) is handled for you.

```kotlin
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.lightningserver.auth.noAuth

val liveFeed = path.path("ws").path("feed") bind ApiWebsocketHandler<_, Unit, User?, FeedRequest, FeedUpdate>(
    summary = "Live Feed",
    description = "Streams feed updates to the client.",
    auth = noAuth,
    willConnectType = { access -> Unit },
    messageFromClientType = { request: FeedRequest ->
        send(FeedUpdate(/* ... */))   // send takes a typed OUTPUT
    },
)
```

Inside the typed callbacks, `auth()` resolves the connection's authenticated principal, and `send`, `subscribe`,
and the state helpers behave as they do for raw handlers.

`ModelRestUpdatesWebsocket` (and the combined `ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)`) build
on this to push live database changes to clients — see the model REST documentation.

## Local vs. AWS execution models

The lifecycle above is identical across engines, but the machinery underneath differs:

- **Local engines (Ktor, Netty, JDK).** Everything runs in one process. Handlers that implement
  `DirectExecutableWebSocketHandler` are driven directly, bypassing pub/sub overhead. Per-connection state and
  subscriptions are kept in-process.
- **AWS (API Gateway + Lambda).** Each frame may be handled by a different Lambda instance, so nothing can rely on
  local memory. The `STORAGE` object is serialized and stored (in DynamoDB), subscriptions are tracked there too,
  and outbound messages are routed either directly to the API Gateway connection (via the connection's
  `engineSocketId`) or through the pub/sub service. This is why `STORAGE` must be `@Serializable` and why
  cross-connection messaging goes through topics rather than direct references.

Writing to the lifecycle/topic API keeps your code portable between the two.
