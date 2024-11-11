package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.serialization.contextualSerializerIfHandled
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.auth.authChecked
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.utils.cancellingScope
import com.lightningkite.lightningserver.websocket.MidWebsocket
import com.lightningkite.lightningserver.websocket.TypeRetriever
import com.lightningkite.lightningserver.websocket.VirtualSocket
import com.lightningkite.lightningserver.websocket.WebSocketClose
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.lightningserver.websocket.WebSocketFrame
import com.lightningkite.lightningserver.websocket.WebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSocketTopic
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.test
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.net.URLDecoder
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

abstract class ApiWebsocket<USER : HasId<*>?, PATH : TypedServerPath, INPUT, OUTPUT, STORAGE> : Documentable {
    abstract override val path: PATH
    abstract override val authOptions: AuthOptions<USER>
    abstract val inputType: KSerializer<INPUT>
    abstract val outputType: KSerializer<OUTPUT>
    abstract val storageSerializer: KSerializer<STORAGE>
    abstract override val summary: String
    open override val description: String get() = summary
    open val errorCases: List<LSError> get() = emptyList()
    open override val belongsToInterface: Documentable.InterfaceInfo? get() = null

    abstract suspend fun AuthAndPathParts<USER, PATH>.willConnect(request: WebSocketConnectRequest): STORAGE
    open suspend fun didConnect(connection: Mid<USER, PATH, INPUT, OUTPUT, STORAGE>, request: WebSocketConnectRequest) {}
    open suspend fun messageFromClient(connection: Mid<USER, PATH, INPUT, OUTPUT, STORAGE>, input: INPUT) {}
    open suspend fun messageFromSubscription(connection: Mid<USER, PATH, INPUT, OUTPUT, STORAGE>,
        topic: String,
        retriever: TypeRetriever
    ) {
    }

    open suspend fun disconnect(connection: Mid<USER, PATH, INPUT, OUTPUT, STORAGE>, reason: WebSocketClose) {}

    interface Mid<USER : HasId<*>?, PATH : TypedServerPath, INPUT, OUTPUT, STORAGE> {
        val currentState: STORAGE
        suspend fun repullState(): STORAGE
        suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE): STORAGE
        suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE
        suspend fun <T> subscribe(topic: WebSocketTopic<T>)
        suspend fun unsubscribe(topic: String)
        suspend fun send(output: OUTPUT)
        suspend fun close(reason: WebSocketClose)
    }

    inner class MidImpl(val wraps: MidWebsocket<ApiWebsocketStorage<STORAGE>>) :
        Mid<USER, PATH, INPUT, OUTPUT, STORAGE> {
        override val currentState: STORAGE = wraps.currentState.storage
        override suspend fun repullState(): STORAGE = wraps.repullState().storage
        override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE): STORAGE {
            return wraps.queueStateUpdate { it.copy(storage = modification(it.storage)) }.storage
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

    val raw by lazy {
        object : WebSocketHandler<ApiWebsocketStorage<STORAGE>> {
            override val storageSerializer: KSerializer<ApiWebsocketStorage<STORAGE>> =
                ApiWebsocketStorage.serializer(this@ApiWebsocket.storageSerializer)

            override suspend fun willConnect(request: WebSocketConnectRequest): ApiWebsocketStorage<STORAGE> =
                ApiWebsocketStorage(
                    request.queryParameter(HttpHeader.Accept)
                        ?: request.queryParameter(HttpHeader.ContentType)
                        ?: request.headers.contentType?.toString()
                        ?: request.headers.accept.firstOrNull()?.toString()
                        ?: ContentType.Application.Json.toString(),
                    with(path.authAndPathParts(request, authOptions)) { willConnect(request) }
                )

            override suspend fun didConnect(
                connection: MidWebsocket<ApiWebsocketStorage<STORAGE>>,
                request: WebSocketConnectRequest
            ) = didConnect(MidImpl(connection), request)

            override suspend fun messageFromClient(
                connection: MidWebsocket<ApiWebsocketStorage<STORAGE>>,
                frame: WebSocketFrame
            ) = messageFromClient(MidImpl(connection), connection.currentState.parser(frame, inputType))

            override suspend fun messageFromSubscription(
                connection: MidWebsocket<ApiWebsocketStorage<STORAGE>>,
                topic: String,
                retrieve: TypeRetriever
            ) = messageFromSubscription(MidImpl(connection), topic, retrieve)

            override suspend fun disconnect(
                connection: MidWebsocket<ApiWebsocketStorage<STORAGE>>,
                reason: WebSocketClose
            ) = this@ApiWebsocket.disconnect(MidImpl(connection), reason)
        }
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
        val mid = object : ApiWebsocket.Mid<USER, PATH, INPUT, OUTPUT, STORAGE> {
            override var currentState: STORAGE = startingState
            override suspend fun repullState(): STORAGE = currentState
            override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE): STORAGE {
                currentState = modification(currentState)
                return currentState
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
        didConnect(mid, req)

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
