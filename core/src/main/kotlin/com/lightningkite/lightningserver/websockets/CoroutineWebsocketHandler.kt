package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.websockets.CoroutineWebSocketHandler.SerializableWebSocketFrame.Companion.serializable
import com.lightningkite.lightningserver.websockets.CoroutineWebSocketHandler.SerializableWebSocketFrame.Companion.standard
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("CoroutineWebSocketHandler")

public abstract class CoroutineWebSocketHandler : ServerBuilder() {
    protected abstract val pubSub: Runtime<PubSub>

    @Serializable
    public data class Storage(
        val id: Uuid = Uuid.random(),
        val request: WebSocketConnectRequest<@Contextual PathSpec0>,
    )

    // Scope for background task execution (in tests, tasks need to run asynchronously)
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Gets the inbound PubSub channel for this connection.
     *
     * Note: We intentionally do NOT cache channels at the handler level to support
     * stateless serverless environments (e.g., AWS Lambda) where each WebSocket message
     * may be handled by a different instance. The PubSub implementation handles any
     * necessary channel deduplication internally.
     */
    context(runtime: ServerRuntime)
    private fun Storage.inbound(): PubSubChannel<SerializableWebSocketFrame> =
        pubSub().get("ws-in-${id}", SerializableWebSocketFrame.serializer())

    /**
     * Gets the direct WebSocket sender from the runtime, if available.
     */
    context(runtime: ServerRuntime)
    private fun getDirectSender(): DirectWebSocketSender? = runtime.directWebSocketSender

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
        send: suspend (WebSocketFrame) -> Unit,
    )

    public val outboundTopic: WebSocketTopic<PathSpec1<Uuid>, SerializableWebSocketFrame> =
        path.path("ws-out").arg<Uuid>("connectionId").topic(SerializableWebSocketFrame.serializer())

    public val task: Task<Storage> = path.path("ws-controller") bind Task(Storage.serializer()) { storage ->
        logger.info { "Task STARTING for connection ${storage.id}" }
        // Capture direct send capability from context before creating lambdas
        val directSender = getDirectSender()
        val socketId = storage.request.engineSocketId
        try {
            handle(
                request = storage.request,
                waitForFullConnect = {
                    // Send empty frame to indicate connection is ready
                    logger.info { "Task ${storage.id}: emitting ready signal to inbound channel" }
                    storage.inbound().emit(SerializableWebSocketFrame())
                    logger.info { "Task ${storage.id}: ready signal emitted" }
                },
                incoming = storage.inbound().mapNotNull {
                    if (it.close) throw CancellationException("Client closed connection.")
                    // Skip empty ready signal frames (both string and data are null)
                    if (it.string == null && it.data == null) return@mapNotNull null
                    it.standard()
                },
                send = { frame ->
                    if (directSender != null && socketId != null) {
                        // Direct send - bypasses Lambda/DynamoDB pub/sub overhead
                        if (!directSender.sendDirect(socketId, frame)) {
                            // Connection gone, signal close
                            storage.inbound().emit(SerializableWebSocketFrame(close = true))
                        }
                    } else {
                        // Fallback to topic-based send (for non-AWS engines or missing engineSocketId)
                        outboundTopic.send(storage.id, frame.serializable())
                    }
                }
            )
            logger.info { "Task COMPLETED normally for connection ${storage.id}" }
        } catch (e: CancellationException) {
            logger.info { "Task COMPLETED via cancellation for connection ${storage.id}" }
            storage.inbound().emit(SerializableWebSocketFrame(close = true, failure = null))
        } catch (e: HttpStatusException) {
            logger.info { "Task COMPLETED via HttpStatusException for connection ${storage.id}: ${e.message}" }
            storage.inbound().emit(SerializableWebSocketFrame(close = true, failure = e.message, status = e.status))
        } catch (e: Exception) {
            logger.info { "Task COMPLETED via Exception for connection ${storage.id}: ${e.message}" }
            storage.inbound().emit(SerializableWebSocketFrame(close = true, failure = e.message ?: e::class.simpleName))
        }
    }

    public val webSocketHandler: WebSocketHandler<PathSpec0, Storage> =
        path bind (object : WebSocketHandler<PathSpec0, Storage>, DirectExecutableWebSocketHandler<PathSpec0> {

            override val storageSerializer: KSerializer<Storage> = Storage.serializer()

            // --- DirectExecutableWebSocketHandler implementation ---
            // Used by local engines (Ktor, Netty) to bypass pub/sub overhead

            override suspend fun handleDirect(
                serverRuntime: ServerRuntime,
                request: WebSocketConnectRequest<PathSpec0>,
                incoming: ReceiveChannel<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
                close: suspend (WebSocketClose) -> Unit,
            ) {
                logger.info { "handleDirect: starting direct execution (bypassing pub/sub)" }
                try {
                    with(serverRuntime) {
                        handle(
                            request = request,
                            waitForFullConnect = { /* Already connected in direct mode */ },
                            incoming = flow {
                                incoming.consumeEach { emit(it) }
                            },
                            send = send
                        )
                    }
                    logger.info { "handleDirect: handler completed normally" }
                    close(WebSocketClose.NORMAL)
                } catch (e: CancellationException) {
                    logger.info { "handleDirect: cancelled" }
                    close(WebSocketClose.GOING_AWAY)
                } catch (e: HttpStatusException) {
                    logger.warn(e) { "handleDirect: HTTP exception" }
                    close(e.status.bestWebSocketCloseCode)
                } catch (e: Exception) {
                    logger.error(e) { "handleDirect: unexpected exception" }
                    close(WebSocketClose.INTERNAL_ERROR)
                }
            }

            // --- Standard WebSocketHandler implementation ---
            // Used by distributed engines (AWS Lambda) that need pub/sub

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun didConnect() {
                // Only subscribe to outbound topic if direct send is not available
                // This avoids unnecessary DynamoDB subscription when using direct API Gateway
                if (connection.currentState.request.engineSocketId == null) {
                    connection.subscribe(outboundTopic.request(connection.currentState.id))
                }
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
                        s.inbound().first()
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
                connection.currentState.inbound().emit(frame.serializable())
            }

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>) {
                if (topic.topic == outboundTopic) {
                    connection.send((topic.value as SerializableWebSocketFrame).standard())
                }
            }

            context(connection: WebSocketConnection<PathSpec0, Storage>)
            override suspend fun disconnect(reason: WebSocketClose) {
                connection.currentState.inbound().emit(SerializableWebSocketFrame(close = true))
            }
        })

}