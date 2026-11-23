package com.lightningkite.lightningserver.guide.samples

// region websockets-imports
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
// endregion websockets-imports

// region ws-echo
object WsEchoServer : ServerBuilder() {
    // ws://.../echo
    val echo = path.path("echo") bind WebSocketHandler(
        willConnect = { Unit },
        didConnect = { send("Echo server ready") },
        messageFromClient = { frame -> send("Echo: ${frame.text}") },
    )
}
// endregion ws-echo

// region ws-echo-test
fun wsEchoTest() = WsEchoServer.testBlocking(settings = {}) {
    val received = mutableListOf<String>()
    val ws = WsEchoServer.echo.test()
    ws.onMessageSent = { frame -> received.add(frame.text) }

    ws.send(WebSocketFrame("hello"))
    check(received == listOf("Echo: hello"))
    ws.close()
}
// endregion ws-echo-test

// region ws-chat
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
// endregion ws-chat

// region ws-chat-test
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
// endregion ws-chat-test

// region ws-typed
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
// endregion ws-typed

// region ws-typed-test
fun wsTypedTest() = WsPriceServer.testBlocking(settings = {}) {
    val received = mutableListOf<String>()
    val ws = WsPriceServer.prices.test()
    ws.onMessageSent = { received.add(it.text) }

    ws.send(WebSocketFrame("""{"symbol":"LK"}"""))
    check(received == listOf("""{"symbol":"LK","cents":12345}"""))
    ws.close()
}
// endregion ws-typed-test

// region ws-multiplex
object WsAppServer : ServerBuilder() {
    val chat = path.path("chat") include WsChatServer

    // One physical socket that carries many logical ones.
    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()
}
// endregion ws-multiplex

// region ws-coroutine
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
// endregion ws-coroutine
