package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.OverrideOnly
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
import kotlin.time.Duration.Companion.milliseconds
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
        // Capture direct send capability from context before creating lambdas
        val directSender = getDirectSender()
        val socketId = storage.request.engineSocketId
        try {
            handle(
                request = storage.request,
                waitForFullConnect = {
                    // Send empty frame to indicate connection is ready
                    storage.inbound().emit(SerializableWebSocketFrame())
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
        } catch (e: CancellationException) {
            storage.inbound().emit(SerializableWebSocketFrame(close = true, failure = null))
        } catch (e: HttpStatusException) {
            storage.inbound().emit(SerializableWebSocketFrame(close = true, failure = e.message, status = e.status))
        } catch (e: Exception) {
            storage.inbound().emit(SerializableWebSocketFrame(close = true, failure = e.message ?: e::class.simpleName))
        }
    }

    public val webSocketHandler: WebSocketHandler<PathSpec0, Storage> =
        path bind (object : WebSocketHandler<PathSpec0, Storage>, DirectExecutableWebSocketHandler<PathSpec0, Storage> {

            override val storageSerializer: KSerializer<Storage> = Storage.serializer()

            // --- DirectExecutableWebSocketHandler implementation ---
            // Used by local engines (Ktor, Netty) to bypass pub/sub overhead

            @OverrideOnly
            context(server: ServerRuntime)
            override suspend fun handleDirect(
                request: WebSocketConnectRequest<PathSpec0>,
                incoming: ReceiveChannel<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
                close: suspend (WebSocketClose) -> Unit,
            ) {
                try {
                    handle(
                        request = request,
                        waitForFullConnect = { /* Already connected in direct mode */ },
                        incoming = flow {
                            incoming.consumeEach { emit(it) }
                        },
                        send = send
                    )
                    close(WebSocketClose.NORMAL)
                } catch (_: CancellationException) {
                    close(WebSocketClose.GOING_AWAY)
                    currentCoroutineContext().ensureActive()
                } catch (e: HttpStatusException) {
                    close(WebSocketClose.exceptional(e))
                } catch (e: Exception) {
                    logger.error(e) { "handleDirect: unexpected exception" }
                    close(WebSocketClose.exceptional(e))
                }
            }

            // --- Standard WebSocketHandler implementation ---
            // Used by distributed engines (AWS Lambda) that need pub/sub

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun didConnect(connection: WebSocketConnection<PathSpec0, Storage>) {
                // Only subscribe to outbound topic if direct send is not available
                // This avoids unnecessary DynamoDB subscription when using direct API Gateway
                if (connection.currentState.request.engineSocketId == null) {
                    connection.subscribe(outboundTopic.request(connection.currentState.id))
                }
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PathSpec0>): Storage = coroutineScope {
                val s = Storage(request = request)

                // Start collecting from inbound BEFORE starting the task
                val startupMessageDeferred = async {
                    // Increased timeout to handle Lambda cold starts + DynamoDB Task scheduling + PubSub latency
                    withTimeout(25_000.milliseconds) {
                        s.inbound().first()
                    }
                }

                // Give the async block a moment to start collecting
                // Increased for DynamoDB-based PubSub which may have subscription latency
                delay(100.milliseconds)

                // Launch the background task in a separate coroutine so it doesn't block
                // This allows the task to run `incoming.collect {}` without blocking willConnect
                taskScope.launch {
                    with(serverRuntime) {
                        task(s)
                    }
                }

                // Wait for the startup message
                val startupMessage = try {
                    startupMessageDeferred.await()
                } catch (e: Exception) {
                    logger.error(e) { "willConnect ${s.id}: timeout or error waiting for startup message" }
                    throw Exception("Background task failed to start: ${e.message}", e)
                }

                if (startupMessage.close) {
                    throw Exception(startupMessage.failure ?: "Background task failed to start")
                }
                return@coroutineScope s
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun messageFromClient(
                connection: WebSocketConnection<PathSpec0, Storage>,
                frame: WebSocketFrame,
            ) {
                connection.currentState.inbound().emit(frame.serializable())
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun messageFromSubscription(
                connection: WebSocketConnection<PathSpec0, Storage>,
                topic: WebSocketSubscriptionMessage<*, *>,
            ) {
                if (topic.topic == outboundTopic) {
                    connection.send((topic.value as SerializableWebSocketFrame).standard())
                }
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun disconnect(
                connection: WebSocketConnection<PathSpec0, Storage>,
                reason: WebSocketClose,
            ) {
                connection.currentState.inbound().emit(SerializableWebSocketFrame(close = true))
            }
        })

}