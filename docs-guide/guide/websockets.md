# WebSockets

Bind a `WebSocketHandler` to a path to accept socket connections.  The handler is a set of lifecycle callbacks plus a
serializable per-connection state, so the same code runs on a single process and on serverless engines where every
frame may reach a different instance.  Broadcasts go through typed pub/sub topics.

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-echo -->
```kotlin
object WsEchoServer : ServerBuilder() {
    // ws://.../echo
    val echo = path.path("echo") bind WebSocketHandler(
        willConnect = { Unit },
        didConnect = { send("Echo server ready") },
        messageFromClient = { frame -> send("Echo: ${frame.text}") },
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-echo-test -->
```kotlin
fun wsEchoTest() = WsEchoServer.testBlocking(settings = {}) {
    val received = mutableListOf<String>()
    val ws = WsEchoServer.echo.test()
    ws.onMessageSent = { frame -> received.add(frame.text) }

    ws.send(WebSocketFrame("hello"))
    check(received == listOf("Echo: hello"))
    ws.close()
}
```

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#websockets-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
```

## Lifecycle

| Callback | Receiver / context | Runs when |
|---|---|---|
| `willConnect(request)` | `ServerRuntime` receiver | A client asks to connect.  Returns the connection's initial state; throwing rejects the connection |
| `didConnect` | the connection, with a `ServerRuntime` in context | The connection is open |
| `messageFromClient(frame)` | the connection | A frame arrives (`WebSocketFrame.Text` or `.Binary`; `frame.text` gives a string either way) |
| `topicHandlers { }` | the connection | A message arrives on a subscribed topic |
| `disconnect(reason)` | the connection | The socket closed, from either side |

Inside the connection callbacks:

- `currentState` is the state `willConnect` returned.  Change it with `updateStateImmediately { old -> new }`, or
  `queueStateUpdate { }` to write it once the callback finishes.
- `send(text)`, `send(bytes)`, `send(frame)` send to this client.
- `subscribe(topic, pathArgs...)` / `unsubscribe(...)` join and leave topics.
- `close(reason)` closes the socket, with a `WebSocketClose` such as `WebSocketClose.NORMAL` or
  `WebSocketClose(WebSocketClose.Code.VIOLATED_POLICY, "Not allowed")`.
- `request` is the original `WebSocketConnectRequest`: path arguments, query parameters, headers.

Each phase is its own execution (on AWS, its own Lambda invocation), sharing only the connection's `socketId` and its
state.  Keep anything a later phase needs in the state, not in a local variable or an in-memory map.  The state's type
must be `@Serializable` for the same reason.

## Topics and per-connection state

A topic is a typed channel declared on a path.  Connections subscribe to it; anything with a `ServerRuntime` (an HTTP
handler, a task, another socket) publishes to it, and every subscriber's handler runs.  A topic with path arguments is
one channel per value, here one per room:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-chat -->
```kotlin
@Serializable
data class ChatMember(val room: String, val name: String)

object WsChatServer : ServerBuilder() {
    // One channel per room id.
    val roomTopic = path.path("room-topic").arg<String>("room").topic(String.serializer())

    // ws://.../rooms/{room}?name=...
    val room = path.path("rooms").arg<String>("room") bind WebSocketHandler(
        willConnect = { request ->
            val name = request.queryParameters["name"] ?: throw BadRequestException("Missing name")
            ChatMember(room = request.path.arg1, name = name)
        },
        didConnect = { subscribe(roomTopic, currentState.room) },
        messageFromClient = { frame ->
            roomTopic.send(currentState.room, "${currentState.name}: ${frame.text}")
        },
        topicHandlers = {
            roomTopic bind { message -> send(message.value) }
        },
    )

    // POST /rooms/{room}/announce — any code with a ServerRuntime can publish
    val announce = path.path("rooms").arg<String>("room").path("announce").post bind HttpHandler { request ->
        roomTopic.send(request.path.arg1, "Announcement: ${request.body?.text()}")
        HttpResponse()
    }
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-chat-test -->
```kotlin
fun wsChatTest() = WsChatServer.testBlocking(settings = {}) {
    val lobby = mutableListOf<String>()
    val other = mutableListOf<String>()
    val ann = WsChatServer.room.test("lobby", queryParameters = QueryParameters.parse("name=Ann"))
    val bob = WsChatServer.room.test("lobby", queryParameters = QueryParameters.parse("name=Bob"))
    val cy = WsChatServer.room.test("garden", queryParameters = QueryParameters.parse("name=Cy"))
    bob.onMessageSent = { lobby.add(it.text) }
    cy.onMessageSent = { other.add(it.text) }

    ann.send(WebSocketFrame("hi"))
    check(lobby == listOf("Ann: hi"))
    check(other.isEmpty())

    // Publishing needs a ServerRuntime, which execute { } provides in a test.
    execute { WsChatServer.roomTopic.send("garden", "Watering at noon") }
    check(other == listOf("Watering at noon"))

    ann.close()
    bob.close()
    cy.close()
}
```

The test runner delivers frames and topic messages synchronously, so checks right after `send` are safe.  Pass path
arguments to `.test()` first, then `queryParameters`, `headers`, and so on.

Subscriptions end when the socket closes.

## Typed sockets

`ApiWebSocketHandler` (in `typed`) decodes each frame into an input type, encodes what you `send` from an output type,
checks an auth requirement on connect, and appears in generated SDKs:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-typed -->
```kotlin
@Serializable
data class PriceRequest(val symbol: String)

@Serializable
data class PriceUpdate(val symbol: String, val cents: Long)

object WsPriceServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val prices = path.path("prices") bind ApiWebSocketHandler(
        summary = "Prices",
        description = "Send a symbol; receive its price.",
        auth = noAuth,
        willConnectType = { _ -> Unit },
        messageFromClientType = { request: PriceRequest ->
            send(PriceUpdate(request.symbol, cents = 12_345))
        },
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-typed-test -->
```kotlin
fun wsTypedTest() = WsPriceServer.testBlocking(settings = {}) {
    val received = mutableListOf<String>()
    val ws = WsPriceServer.prices.test()
    ws.onMessageSent = { received.add(it.text) }

    ws.send(WebSocketFrame("""{"symbol":"LK"}"""))
    check(received == listOf("""{"symbol":"LK","cents":12345}"""))
    ws.close()
}
```

- The callbacks are `willConnectType`, `didConnectType`, `messageFromClientType`, `topicHandlersType`, and
  `disconnectType`.  `willConnectType` receives an access object (`access.request`, `access.authOrNull`); the others can
  call `auth()` for the caller.
- The format is the client's `Accept` or `Content-Type` header, or the `Accept` / `Content-Type` query parameter, since
  browsers can't set headers on sockets.  It defaults to JSON.
- A blank text frame is a keep-alive: the server answers with `" "` instead of decoding it.
- A frame that doesn't decode closes the socket as a bad request.

`ModelRestUpdatesWebSocket` builds on this to stream database changes; see [Model realtime](model-realtime.md).

## Authentication

Browsers can't set an `Authorization` header on a WebSocket, so on a WebSocket connect the session manager also reads
the access token from an `Authorization` or `jwt` query parameter (`wss://api.example.com/rooms/lobby?jwt=...`).
Prefer the header where the client can set one: URLs end up in logs.  Plain HTTP requests with a token in the URL are
rejected with 400.  See [Authentication](auth.md).

## Multiplexing

`MultiplexWebSocketHandler` lets a client open many logical sockets over one connection:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-multiplex -->
```kotlin
object WsAppServer : ServerBuilder() {
    val chat = path.path("chat") include WsChatServer

    // One physical socket that carries many logical ones.
    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()
}
```

Every frame on it is a JSON `MultiplexMessage` (a binary frame from the client is one in `KotlinWireFormat`):

```json
{ "channel": "c1", "path": "/chat/rooms/lobby", "queryParams": { "name": ["Ann"] }, "start": true }
{ "channel": "c1", "data": "hi" }
{ "channel": "c1", "end": true }
```

`start` opens a virtual socket on `path` (any socket endpoint on the server; the server echoes the `start` back once it
is open), `data` carries a frame in either direction, and `end` closes it.  A failure comes back as an `end` message
with an `error`.  Query parameters of the physical socket apply to every virtual one.  Virtual sockets run the normal
lifecycle; `WebSocketInterceptor`s wrap only the physical socket, while `ExecutionInterceptor`s (access logs, auditing)
see every virtual phase.

## Coroutine-style handlers

When a socket reads more naturally as a loop than as callbacks, extend `CoroutineWebSocketHandler` and `include` it.  It
runs `handle` as one coroutine per connection, with frames as a `Flow`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/WebSocketsSamples.kt#ws-coroutine -->
```kotlin
object WsCoroutineServer : ServerBuilder() {
    val pubsub = setting("pubsub", PubSub.Settings())

    // ws://.../count
    val counter = path.path("count") include object : CoroutineWebSocketHandler() {
        override val pubSub = this@WsCoroutineServer.pubsub

        context(serverRuntime: ServerRuntime)
        override suspend fun handle(
            request: WebSocketConnectRequest<PathSpec0>,
            waitForFullConnect: suspend () -> Unit,
            incoming: Flow<WebSocketFrame>,
            send: suspend (WebSocketFrame) -> Unit,
        ) {
            waitForFullConnect()
            var count = 0
            incoming.collect { frame ->
                count++
                send(WebSocketFrame("$count: ${frame.text}"))
            }
        }
    }
}
```

Call `waitForFullConnect()` before sending.  On local engines `handle` runs directly against the socket; on serverless
engines it runs as a task and frames travel through the given `PubSub`, so that must be a shared backend (such as
Redis) in production.

## Settings

| Setting | Default | Purpose |
|---|---|---|
| `webSocketSettings.ping` | 30 seconds | How often the server pings each socket; `null` disables pings |
| `webSocketSettings.pongTimeout` | 15 seconds | How long a ping may go unanswered before the socket is closed |
| `webSocketSettings.maxFrameSize` | 16 MiB | Largest frame in either direction; `null` is unbounded |
| `engine-pubSub` | `local` | Pub/sub backend for topic delivery on local engines.  Use a shared one (`redis://...`) when running several instances |
| `forceWebSocketPubSub` | `false` | Route sockets through pub/sub on local engines too, to exercise the serverless path |

The ping defaults are stricter than the engines' own: without pings, a client that stops reading holds buffers forever.

## Engines

The lifecycle above is the same everywhere; the machinery differs.

- **Local engines** (Ktor, Netty, JDK) keep sockets in process.  Topic messages go through `engine-pubSub`, so several
  instances need a shared backend.
- **AWS (API Gateway + Lambda)** stores each connection's state and subscriptions in DynamoDB, and each phase may run on
  a different Lambda.  That is why the state is serializable and cross-connection messages use topics.  The state is
  stored in `KotlinWireFormat`, so it survives adding fields with defaults; a socket whose row an incompatible release
  wrote is closed on its next event, and the client reconnects.

See [Engines](engines.md).

## Next

- [Model realtime](model-realtime.md): live database queries over a socket.
- [Pub/sub](pubsub.md): the pub/sub service behind topics.
- [CORS and interceptors](cors-interceptors.md): `WebSocketInterceptor` and origin checks.
- [Client SDKs](client-sdks.md): typed sockets as `ClientWebSocket` in Kotlin clients.
