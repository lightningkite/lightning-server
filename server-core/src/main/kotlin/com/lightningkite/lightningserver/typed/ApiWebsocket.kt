package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.TypeRetriever
import com.lightningkite.lightningserver.utils.cancellingScope
import com.lightningkite.lightningserver.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class ApiWebsocketStorage<STORAGE>(
    val mimeType: String,
    val storage: STORAGE
) {
    @Transient
    val contentType = ContentType(mimeType)

    @Transient
    val emitter = Serialization.emitters[contentType] ?: throw IllegalStateException("No emitter found for $contentType")

    @Transient
    val parser = Serialization.parsers[contentType] ?: throw IllegalStateException("No parser found for $contentType")
}

abstract class ApiWebsocket<USER : HasId<*>?, PATH : TypedServerPath, INPUT, OUTPUT, STORAGE> constructor(override val path: PATH, val storageSerializer: KSerializer<STORAGE>) : Documentable {
    abstract override val authOptions: AuthOptions<USER>
    abstract val inputType: KSerializer<INPUT>
    abstract val outputType: KSerializer<OUTPUT>
    abstract override val summary: String
    open override val description: String get() = summary
    open val errorCases: List<LSError> get() = emptyList()
    open override val belongsToInterface: Documentable.InterfaceInfo? get() = null

    abstract suspend fun AuthAndPathParts<USER, PATH>.willConnect(request: WebSocketConnectRequest): STORAGE
    open suspend fun didConnect(connection: ApiWebsocketConnection<USER, PATH, INPUT, OUTPUT, STORAGE>) {}
    open suspend fun messageFromClient(connection: ApiWebsocketConnection<USER, PATH, INPUT, OUTPUT, STORAGE>, input: INPUT) {}
    open suspend fun messageFromSubscription(connection: ApiWebsocketConnection<USER, PATH, INPUT, OUTPUT, STORAGE>,
                                             topic: String,
                                             retriever: TypeRetriever
    ) {
    }

    open suspend fun disconnect(connection: ApiWebsocketConnection<USER, PATH, INPUT, OUTPUT, STORAGE>, reason: WebSocketClose) {}

    abstract class ApiWebsocketConnection<USER : HasId<*>?, PATH : TypedServerPath, INPUT, OUTPUT, STORAGE>(
//        authOrNull: RequestAuth<USER & Any>?,
//        rawRequest: Request?,
//        parts: Array<Any?>
    )
//        : AuthAndPathParts<USER, PATH>(authOrNull, rawRequest, parts)  // TODO
    {
        abstract val currentState: STORAGE
        abstract suspend fun repullState(): STORAGE
        abstract suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE)
        abstract suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE
        abstract suspend fun <T> subscribe(topic: WebSocketTopic<T>)
        abstract suspend fun unsubscribe(topic: String)
        abstract suspend fun send(output: OUTPUT)
        abstract suspend fun close(reason: WebSocketClose)
    }

    inner class ApiWebsocketConnectionImpl(val wraps: WebSocketConnection<ApiWebsocketStorage<STORAGE>>) :
        ApiWebsocketConnection<USER, PATH, INPUT, OUTPUT, STORAGE>() {
        override val currentState: STORAGE = wraps.currentState.storage
        override suspend fun repullState(): STORAGE = wraps.repullState().storage
        override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
            return wraps.queueStateUpdate { it.copy(storage = modification(it.storage)) }
        }

        override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
            return wraps.updateStateImmediately { it.copy(storage = modification(it.storage)) }.storage
        }

        override suspend fun <T> subscribe(topic: WebSocketTopic<T>) = wraps.subscribe(topic)
        override suspend fun unsubscribe(topic: String) = wraps.unsubscribe(topic)
        override suspend fun send(output: OUTPUT) {
            wraps.send(wraps.currentState.emitter.ws(wraps.currentState.contentType, outputType, output))
        }

        override suspend fun close(reason: WebSocketClose) = wraps.close(reason)
    }

    val raw = object : WebSocketHandler<ApiWebsocketStorage<STORAGE>> {
        override val storageSerializer: KSerializer<ApiWebsocketStorage<STORAGE>> =
            ApiWebsocketStorage.serializer(this@ApiWebsocket.storageSerializer)

        override suspend fun willConnect(request: WebSocketConnectRequest): ApiWebsocketStorage<STORAGE> =
            ApiWebsocketStorage(
                (request.queryParameterCaseInsensitive(HttpHeader.Accept)
                    ?: request.queryParameterCaseInsensitive(HttpHeader.ContentType)
                    ?: request.headers.contentType?.toString()
                    ?: request.headers.accept.firstOrNull()?.takeUnless { it == ContentType.Any }?.toString()
                    ?: ContentType.Application.Json.toString()),
                with(path.authAndPathParts(request, authOptions)) { willConnect(request) }
            )

        override suspend fun didConnect(
            connection: WebSocketConnection<ApiWebsocketStorage<STORAGE>>
        ) = didConnect(ApiWebsocketConnectionImpl(connection))

        override suspend fun messageFromClient(
            connection: WebSocketConnection<ApiWebsocketStorage<STORAGE>>,
            frame: WebSocketFrame
        ) {
            if ((frame as? WebSocketFrame.Text)?.content?.isBlank() == true) {
                connection.send(" ")
                return
            } else messageFromClient(ApiWebsocketConnectionImpl(connection), connection.currentState.parser(frame, inputType))
        }

        override suspend fun messageFromSubscription(
            connection: WebSocketConnection<ApiWebsocketStorage<STORAGE>>,
            topic: String,
            retrieve: TypeRetriever
        ) = messageFromSubscription(ApiWebsocketConnectionImpl(connection), topic, retrieve)

        override suspend fun disconnect(
            connection: WebSocketConnection<ApiWebsocketStorage<STORAGE>>,
            reason: WebSocketClose
        ) = this@ApiWebsocket.disconnect(ApiWebsocketConnectionImpl(connection), reason)
    }
    init {
        WebSockets.handlers[path.path] = this.raw
    }
}


data class ApiVirtualSocket<IN, OUT>(val incoming: ReceiveChannel<OUT>, val send: suspend (IN) -> Unit)

suspend fun <USER : HasId<*>?, PATH : TypedServerPath, INPUT, OUTPUT, STORAGE> ApiWebsocket<USER, PATH, INPUT, OUTPUT, STORAGE>.test(
    auth: AuthAndPathParts<USER, PATH>,
    test: suspend ApiVirtualSocket<INPUT, OUTPUT>.() -> Unit,
): Unit = cancellingScope {
//    val oldEngine = engine
    engine = UnitTestEngine
    try {
        val cache = LocalCache()
        val req = WebSocketConnectRequest(
            path = path.path,
            parts = path.parameters.withIndex().associate { (index, it) ->
                it.name to Serialization.toString(
                    auth.parts[index],
                    it.serializer as KSerializer<Any?>
                )
            },
            wildcard = null,
            queryParameters = listOf(),
            headers = HttpHeaders.EMPTY,
            domain = "test",
            protocol = "ws",
            sourceIp = "127.0.0.1",
            cache = cache,
        )
        val channel = Channel<OUTPUT>(20)

        val id = UUID.randomUUID().toString()
        println("$id Connecting...")
        val startingState = with(auth) { willConnect(req) }
        val mid = object : ApiWebsocket.ApiWebsocketConnection<USER, PATH, INPUT, OUTPUT, STORAGE>() {
            override var currentState: STORAGE = startingState
            override suspend fun repullState(): STORAGE = currentState
            override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
                currentState = modification(currentState)
            }

            override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
                currentState = modification(currentState)
                return currentState
            }

            val subscriptions = HashMap<String, Job>()
            override suspend fun <T> subscribe(topic: WebSocketTopic<T>) {
                println("$id SUBSCRIBES TO ${topic.topic}")
                subscriptions[topic.topic]?.cancel()
                val t = this
                subscriptions[topic.topic] = this@cancellingScope.launch {
                    LocalPubSub.get(topic.topic, topic.type).collect { value ->
                        messageFromSubscription(
                            connection = t,
                            topic = topic.topic,
                            retriever = TypeRetriever.literal(value)
                        )
                    }
                }
                yield()
            }

            override suspend fun unsubscribe(topic: String) {
                println("$id NO LONGER SUBSCRIBES TO ${topic}")
                subscriptions[topic]?.cancel()
            }

            override suspend fun send(frame: OUTPUT) {
                println("$id <-- $frame")
                channel.send(frame)
            }

            override suspend fun close(reason: WebSocketClose) {
                channel.close()
            }
        }

        println("$id Connected.")
        didConnect(mid)

        var error: Exception? = null
        try {
            test(
                ApiVirtualSocket<INPUT, OUTPUT>(
                    incoming = channel,
                    send = { it: INPUT ->
                        println("$id --> $it")
                        messageFromClient(mid, it)
                    }
                )
            )
        } catch (e: Exception) {
            error = e
        }
        println("$id Disconnecting...")
        disconnect(mid, WebSocketClose.NORMAL)
        println("$id Disconnected.")

        error?.let { throw it }
    } finally {
        cancel()
//        engine = oldEngine
    }
    Unit
}
