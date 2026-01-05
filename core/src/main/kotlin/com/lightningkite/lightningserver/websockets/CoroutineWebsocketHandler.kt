package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.websockets.CoroutineWebsocketHandler.SerializableWebSocketFrame.Companion.serializable
import com.lightningkite.lightningserver.websockets.CoroutineWebsocketHandler.SerializableWebSocketFrame.Companion.standard
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("CoroutineWebsocketHandler")

public abstract class CoroutineWebsocketHandler : ServerBuilder() {
    protected abstract val pubSub: Runtime<PubSub>

    @Serializable
    public data class Storage(
        val id: Uuid = Uuid.random(),
        val request: WebSocketConnectRequest<@Contextual PathSpec0>
    )

    // Cache channels to ensure same instance used for same connection ID
    private val channelCache = mutableMapOf<Uuid, PubSubChannel<SerializableWebSocketFrame>>()

    // Scope for background task execution (in tests, tasks need to run asynchronously)
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    context(runtime: ServerRuntime)
    private val Storage.inbound: PubSubChannel<SerializableWebSocketFrame>
        get() = channelCache.getOrPut(id) {
            pubSub().get(
                "ws-in-${id}",
                SerializableWebSocketFrame.serializer()
            )
        }

    @Serializable
    public data class SerializableWebSocketFrame(
        val string: String? = null,
        val data: ByteArray? = null,
        val close: Boolean = false,
        val failure: String? = null,
        val status: HttpStatus? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SerializableWebSocketFrame && (string == other.string || data?.contentEquals(other.data) == true)

        override fun hashCode(): Int = string?.hashCode() ?: data?.contentHashCode() ?: 0

        public companion object {
            public fun WebSocketFrame.serializable(): SerializableWebSocketFrame = when (this) {
                is WebSocketFrame.Binary -> SerializableWebSocketFrame(data = this.content)
                is WebSocketFrame.Text -> SerializableWebSocketFrame(string = this.content)
            }

            public fun SerializableWebSocketFrame.standard(): WebSocketFrame =
                string?.let { WebSocketFrame.Text(it) } ?: WebSocketFrame.Binary(data!!)
        }
    }

    context(serverRuntime: ServerRuntime)
    public abstract suspend fun handle(
        request: WebSocketConnectRequest<PathSpec0>,
        waitForFullConnect: suspend () -> Unit,
        incoming: Flow<WebSocketFrame>,
        send: suspend (WebSocketFrame) -> Unit
    )

    public val outboundTopic: WebSocketTopic<PathSpec1<Uuid>, SerializableWebSocketFrame> =
        path.path("ws-out").arg<Uuid>("connectionId").topic(SerializableWebSocketFrame.serializer())

    public val task: Task<Storage> = path.path("ws-controller") bind Task(Storage.serializer()) { storage ->
        logger.info { "Task started for connection ${storage.id}" }
        try {
            handle(
                request = storage.request,
                waitForFullConnect = {
                    // Send empty frame to indicate connection is ready
                    logger.info { "Task ${storage.id}: emitting ready signal to inbound channel" }
                    storage.inbound.emit(SerializableWebSocketFrame())
                    logger.info { "Task ${storage.id}: ready signal emitted" }
                },
                incoming = storage.inbound.map {
                    if (it.close) throw CancellationException("Client closed connection.")
                    it.standard()
                },
                send = { frame ->
                    outboundTopic.send(storage.id, frame.serializable())
                }
            )
        } catch (e: CancellationException) {
            storage.inbound.emit(SerializableWebSocketFrame(close = true, failure = null))
        } catch (e: HttpStatusException) {
            storage.inbound.emit(SerializableWebSocketFrame(close = true, failure = e.message, status = e.status))
        } catch (e: Exception) {
            storage.inbound.emit(SerializableWebSocketFrame(close = true, failure = e.message ?: e::class.simpleName))
        }
    }

    public val websocketHandler: WebSocketHandler<PathSpec0, Storage> =
        path bind (object : WebSocketHandler<PathSpec0, Storage> {

            override val storageSerializer: KSerializer<Storage> = Storage.serializer()

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun didConnect() {
                // Subscribe to outbound topic to receive messages from the background task
                connection.subscribe(outboundTopic.request(connection.currentState.id))
            }

            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PathSpec0>): Storage = coroutineScope {
                val s = Storage(request = request)
                logger.info { "willConnect ${s.id}: starting, subscribing to inbound channel" }

                // Start collecting from inbound BEFORE starting the task
                val startupMessageDeferred = async {
                    logger.info { "willConnect ${s.id}: waiting for first message with 25s timeout" }
                    // Increased timeout to handle Lambda cold starts + DynamoDB Task scheduling + PubSub latency
                    withTimeout(25_000) {
                        s.inbound.first()
                    }
                }

                // Give the async block a moment to start collecting
                // Increased for DynamoDB-based PubSub which may have subscription latency
                logger.info { "willConnect ${s.id}: delaying 100ms for subscription setup" }
                delay(100)

                // Launch the background task in a separate coroutine so it doesn't block
                // This allows the task to run `incoming.collect {}` without blocking willConnect
                logger.info { "willConnect ${s.id}: invoking task" }
                taskScope.launch {
                    with(serverRuntime) {
                        task(s)
                    }
                }

                // Wait for the startup message
                logger.info { "willConnect ${s.id}: waiting for startup message" }
                val startupMessage = try {
                    startupMessageDeferred.await()
                } catch (e: Exception) {
                    logger.error(e) { "willConnect ${s.id}: timeout or error waiting for startup message" }
                    throw Exception("Background task failed to start: ${e.message}", e)
                }

                logger.info { "willConnect ${s.id}: received startup message, close=${startupMessage.close}" }
                if (startupMessage.close) {
                    throw Exception(startupMessage.failure ?: "Background task failed to start")
                }
                return@coroutineScope s
            }

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun messageFromClient(frame: WebSocketFrame) {
                connection.currentState.inbound.emit(frame.serializable())
            }

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>) {
                if (topic.topic == outboundTopic) {
                    connection.send((topic.value as SerializableWebSocketFrame).standard())
                }
            }

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun disconnect(reason: WebSocketClose) {
                connection.currentState.inbound.emit(SerializableWebSocketFrame(close = true))
            }
        })

}