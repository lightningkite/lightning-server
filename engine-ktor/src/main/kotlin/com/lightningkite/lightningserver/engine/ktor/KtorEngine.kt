@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.BodyTooLargeException
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.engine.local.WsOversizePolicy
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.engine.local.LocalWebSocketConnection
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.runtime.phase
import com.lightningkite.lightningserver.runtime.didConnectWithMetrics
import com.lightningkite.lightningserver.runtime.disconnectWithMetrics
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.messageFromClientWithMetrics
import com.lightningkite.lightningserver.runtime.willConnectWithMetrics
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.use
import io.ktor.http.*
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.utils.io.asSink
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Configuration settings for the Ktor HTTP server engine.
 *
 * @property host The host address to bind to (defaults to "0.0.0.0" for all interfaces)
 * @property port The port number to listen on (defaults to 8080)
 * @property realIpHeader Optional header name to extract the real client IP from (useful behind proxies).
 *                        Common values: "X-Forwarded-For", "X-Real-IP"
 * @property requestIdHeader Optional header name carrying a request ID stamped by a **trusted**
 *   reverse proxy, adopted as the authoritative request ID. Leave null (the default) to always
 *   generate one; a client-supplied ID is never trusted. Set to "X-Request-ID" behind Envoy so the
 *   proxy's capture and the server's logs share an identifier.
 * @property reliability Shared engine reliability settings (request timeout, max body size, graceful
 *   shutdown drain, WebSocket backpressure). See [EngineReliabilitySettings]. Note that
 *   [EngineReliabilitySettings.idleTimeout] and [EngineReliabilitySettings.workerThreads] are
 *   Netty/JDK-specific and ignored by the Ktor engine.
 */
@Serializable
public data class KtorRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val requestIdHeader: String? = null,
    val reliability: EngineReliabilitySettings = EngineReliabilitySettings(),
)

/**
 * Server setting for configuring the Ktor engine runtime parameters.
 */
public val ktorRunConfig: ServerSetting.Direct<KtorRuntimeSettings> = ServerSetting(
    "ktorRunConfig",
    KtorRuntimeSettings(),
    KtorRuntimeSettings.serializer()
)

/**
 * A Lightning Server engine implementation using Ktor as the HTTP server.
 *
 * This is the recommended engine for local development and production deployment
 * when running on a dedicated server or container. It provides:
 * - Full HTTP/1.1 and HTTP/2 support (depending on Ktor engine choice)
 * - WebSocket support
 * - Background task execution
 * - Scheduled task coordination
 *
 * The engine can be started with different Ktor engine factories (Netty, CIO, Jetty, etc.)
 * using the `start()` method.
 *
 * Example usage:
 * ```kotlin
 * val engine = KtorEngine(Server.build())
 * engine.settings.loadFromFile(File("settings.json"), internalSerializersModule)
 * engine.start(Netty) // or CIO, Jetty, etc.
 * ```
 *
 * @param server The server definition to run
 * @param clock The clock to use for timing operations (defaults to System clock, overridable for testing)
 * @param disableResponseStreaming A lambda that takes a request and returns a Boolean. A return of true will disable
 *      response streaming, meaning a guaranteed known content length in the response headers. This is useful for
 *      responding to IOT devices that require ContentLength in responses due to memory limitations
 */
public class KtorEngine(
    server: ServerDefinition,
    override val clock: Clock = Clock.System,
    private val disableResponseStreaming: (HttpRequest<*>) -> Boolean = { false },
) : LocalEngine(server) {

    override val settings: ServerSettings = super.settings + ktorRunConfig

    /**
     * Adapts a Ktor Application to handle Lightning Server requests.
     * Sets up routing for both HTTP and WebSocket connections.
     */
    internal fun Application.adapt() {
        val wsSettings = webSocketSettings()
        install(WebSockets) {
            // Ktor disables server-side pings by default, and its pong timeout has no effect without
            // them. That leaves no way to notice a peer that has stopped reading: the ping send itself
            // blocks on the backed-up outgoing channel and the timeout closes the connection, so
            // enabling this is what evicts a stalled consumer instead of holding its buffers forever.
            pingPeriodMillis = wsSettings.ping?.inWholeMilliseconds ?: PINGER_DISABLED
            timeoutMillis = wsSettings.pongTimeout.inWholeMilliseconds
            // Ktor's default is Long.MAX_VALUE, i.e. a peer may ask the server to allocate without limit.
            wsSettings.maxFrameSize?.let { maxFrameSize = it.bytes }
        }

        val runConfig = ktorRunConfig()

        val reliability = runConfig.reliability
        val maxBody = reliability.maxBodySize.bytes

        routing {
            route("{...}") {
                handle {
                    // 2.5: reject oversized bodies by declared Content-Length before reading the body.
                    val declaredLength = call.request.contentLength()
                    if (declaredLength != null && declaredLength > maxBody) {
                        call.respondText("Payload Too Large", status = HttpStatusCode.PayloadTooLarge)
                        return@handle
                    }
                    val (request, executionId) = call.adapt(maxBody)
                    // Request timeout is enforced centrally in ServerRuntime.handle (per-handler HttpHandler.timeout).
                    val result: HttpResponse = try {
                        this@KtorEngine.handle(request, executionId)
                    } catch (_: BodyTooLargeException) {
                        // 2.5: streamed body exceeded the cap mid-read.
                        HttpResponse.plainText("Payload Too Large", HttpStatus.PayloadTooLarge)
                    }

                    for (header in result.headers.normalizedEntries) {
                        for (value in header.value) {
                            call.response.header(header.key, value.toHttpString())
                        }
                    }
                    val code = HttpStatusCode.fromValue(result.status.code)
                    val type = result.body?.mediaType?.toString()?.let { ContentType.parse(it) }

                    val body = result.body?.data
                    if (body == null) {
                        val contentType = call.response.headers[HttpHeaders.ContentType]
                        val contentLength = call.response.headers[HttpHeaders.ContentLength]
                        if (contentType != null && contentLength != null) {
                            call.response.call.respondOutputStream(
                                ContentType.parse(contentType),
                                HttpStatusCode.NoContent,
                                contentLength.toLong()
                            ) { close() }
                        } else
                            call.respondText("", type, code) { }
                    } else if (disableResponseStreaming(request))
                        call.respondBytes(body.bytes(), type, code)
                    else
                        when (body) {
                            is Data.Bytes -> call.respondBytes(body.data, type, code)
                            is Data.Text -> call.respondText(body.data, type, code)
                            is Data.Sink -> call.respondBytesWriter(contentType = type, status = code) {
                                // emit is a blocking producer — run it on the IO pool (via Ktor's blocking asSink
                                // bridge) so it streams to the channel without ever stalling the event-loop thread.
                                val channel = this
                                withContext(Dispatchers.IO) { channel.asSink().buffered().use { body.emit(it) } }
                            }
                            is Data.Source -> call.respondBytesWriter(contentType = type, status = code) {
                                // Blocking streaming source: copy it to the channel on the IO pool (no full buffering).
                                val channel = this
                                withContext(Dispatchers.IO) {
                                    channel.asSink().buffered().use { sink -> body.source.use { sink.transferFrom(it) } }
                                }
                            }
                            is Data.SuspendingSource, is Data.SuspendingSink -> call.respondBytesWriter(contentType = type, status = code) {
                                // Fully cooperative: stream the body into the ByteWriteChannel via a SuspendingSink so
                                // response writes suspend for backpressure instead of blocking the event loop.
                                // use() is what turns a mid-body failure into a cancelled channel — without it the
                                // engine would frame a half-written body as a complete response.
                                KtorChannelSuspendingSink(this).use { body.writeSuspending(it) }
                            }
                        }
                }
            }
            webSocket("{...}") {

                // TODO: Remove this fugly hack. It's around for backwards compatibility.
                fun parseQueryParams(): QueryParameters = QueryParameters(
                    call.request.queryParameters.flattenEntries()
                        .flatMap {
                            if (it.first == "path" && it.second.contains('?')) {
                                listOf(it.first to it.second.substringBefore('?')) +
                                        QueryParameters.parse(
                                            it.second.substringAfter('?')
                                        ).entries
                            } else
                                listOf(it)
                        })

                val queryParams = parseQueryParams()
                val adaptedHeaders = call.request.headers.adapt()
                val identity = adaptedHeaders.requestIdentity(runConfig.requestIdHeader) {
                    logger.warn { "Request ID header for proxy '${runConfig.requestIdHeader}' was missing from the request." }
                }
                val request = WebSocketConnectRequest(
                    path = RawWebSocketPath(queryParams["path"] ?: call.request.path().decodeURLPart()),
                    queryParameters = queryParams,
                    headers = adaptedHeaders,
                    upstreamRequestId = identity.upstreamRequestId,
                    domain = call.request.origin.serverHost,
                    protocol = call.request.origin.scheme,
                    sourceIp = runConfig.realIpHeader?.let {
                        call.request.header(it)
                            ?: run { logger.warn { "Real IP address header for proxy '$it' was missing from the request." }; null }
                    } ?: call.request.origin.remoteAddress,
                )

                val match = server.endpoints.match(
                    externalSerialization.stringArrayFormat,
                    request.path.pathSegments
                ) { it.webSocket } ?: run {
                    this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "No matching path found for ${request.path}"
                        )
                    )
                    return@webSocket
                }
                val socketHandler = server.interceptIncomingSocket(match.value)

                // The socket's identity is minted once, here, and every phase below derives its own
                // execution from it, so a socket stays one thing across five separate executions.
                val connectInitiator = Initiator.WebSocket(
                    executionId = identity.requestId,
                    socketId = identity.requestId,
                    path = request.path,
                    phase = Initiator.WebSocket.Phase.Connect,
                )

                // Check for direct execution capability - bypasses pub/sub overhead
                if (socketHandler is DirectExecutableWebSocketHandler<*> && !forceWebSocketPubSub()) {
                    @Suppress("UNCHECKED_CAST")
                    val directHandler = socketHandler as DirectExecutableWebSocketHandler<PathSpec>

                    // 2.10: bounded inbound channel with backpressure instead of Channel.UNLIMITED.
                    val incomingChannel = newWebSocketInboundChannel<WebSocketFrame>(reliability)

                    // Launch coroutine to pipe Ktor frames to our channel
                    launch {
                        try {
                            for (frame in incoming) {
                                val lkFrame = when (frame) {
                                    is Frame.Binary -> WebSocketFrame(frame.data)
                                    is Frame.Text -> WebSocketFrame(frame.readText())
                                    else -> continue // ignore ping/pong/close
                                }
                                if (reliability.webSocketOversizePolicy == WsOversizePolicy.CLOSE) {
                                    // Non-suspending offer: if the bounded buffer is full, the peer is
                                    // outrunning the handler -> close with 1009 (message too big).
                                    val result = incomingChannel.trySend(lkFrame)
                                    if (result.isFailure && !result.isClosed) {
                                        close(CloseReason(1009.toShort(), "WebSocket inbound buffer overflow"))
                                        break
                                    }
                                } else {
                                    // DROP_OLDEST / SUSPEND are handled by the channel's BufferOverflow policy.
                                    incomingChannel.send(lkFrame)
                                }
                            }
                        } finally {
                            incomingChannel.close()
                        }
                    }

                    // Run handler directly - no pub/sub, no task indirection
                    // A directly-run socket is not phase-structured — the whole session runs in this
                    // one coroutine — so it is one execution, named by the socket it is.
                    directHandler.handleDirect(
                        serverRuntime = this@KtorEngine.forExecution(connectInitiator),
                        request = request,
                        incoming = incomingChannel,
                        send = { frame ->
                            when (frame) {
                                is WebSocketFrame.Binary -> send(Frame.Binary(true, frame.content))
                                is WebSocketFrame.Text -> send(Frame.Text(frame.content))
                            }
                        },
                        close = { reason ->
                            close(CloseReason(reason.code, reason.name))
                        }
                    )
                } else {
                    // Standard pub/sub-based implementation (for non-direct handlers or when forced)
                    @Suppress("UNCHECKED_CAST")
                    socketHandler as WebSocketHandler<PathSpec, Any?>

                    val startingState =
                        socketHandler.willConnectWithMetrics(match.pathSpec, this@KtorEngine, connectInitiator, request)
                    var closingMid: WebSocketConnection<PathSpec, Any?>? = null

                    // Disconnect is the socket's cleanup phase, so it has to outlive the cancellation
                    // that ends the socket. Shutting the server down cancels this coroutine, and a
                    // suspending call in a cancelled coroutine fails before it runs anything, which
                    // skipped the handler's cleanup and its span together and left the socket's last
                    // phase with no trace at all.
                    suspend fun emitDisconnect(
                        mid: WebSocketConnection<PathSpec, Any?>,
                        reason: WebSocketClose,
                    ): Unit = withContext(NonCancellable) {
                        socketHandler.disconnectWithMetrics(
                            match.pathSpec,
                            this@KtorEngine,
                            connectInitiator.phase(Initiator.WebSocket.Phase.Disconnect),
                            mid,
                            reason,
                        )
                    }

                    try {

                        val mid = object : LocalWebSocketConnection<PathSpec, Any?>(
                            startingState = startingState,
                            request = request,
                            connectInitiator = connectInitiator,
                            handler = socketHandler,
                            scope = this@webSocket,
                            server = this@KtorEngine,
                            pubSub = { pubSubChannel(it) }
                        ) {
                            override suspend fun send(frame: WebSocketFrame) {
                                this@webSocket.send(
                                    when (frame) {
                                        is WebSocketFrame.Binary -> Frame.Binary(true, frame.content)
                                        is WebSocketFrame.Text -> Frame.Text(frame.content)
                                    }
                                )
                            }

                            override suspend fun close(reason: WebSocketClose) {
                                this@webSocket.close(CloseReason(reason.code, reason.name))
                            }
                        }
                        closingMid = mid

                        socketHandler.didConnectWithMetrics(
                            match.pathSpec,
                            this@KtorEngine,
                            connectInitiator.phase(Initiator.WebSocket.Phase.Connected),
                            mid,
                        )

                        for (incoming in this.incoming) {
                            val m = when (incoming) {
                                is Frame.Binary -> WebSocketFrame(incoming.data)
                                is Frame.Text -> WebSocketFrame(incoming.readText())
                                is Frame.Close -> continue
                                is Frame.Ping -> continue
                                is Frame.Pong -> continue
                            }
                            socketHandler.messageFromClientWithMetrics(
                                match.pathSpec,
                                this@KtorEngine,
                                connectInitiator.phase(Initiator.WebSocket.Phase.ClientMessage),
                                mid,
                                m,
                            )
                        }

                        closingMid.let { mid -> emitDisconnect(mid, WebSocketClose.NORMAL) }
                    } catch (e: Throwable) {
                        closingMid?.let { mid -> emitDisconnect(mid, e.webSocketCloseReason) }
                        // Cleanup above must run for a cancelled socket too — that is what
                        // emitDisconnect's NonCancellable is for — but the cancellation itself has to
                        // keep travelling. Swallowing it would report this coroutine as having
                        // completed normally and leave whoever cancelled us waiting on a child that
                        // never acknowledges the request.
                        if (e is CancellationException) throw e
                    }
                }
            }
        }
    }

    /**
     * Starts the Ktor server with the specified engine factory.
     *
     * This method:
     * 1. Ensures settings are ready and validated
     * 2. Runs any startup tasks defined in the server
     * 3. Starts the schedule coordinator
     * 4. Starts the HTTP server and blocks until shutdown
     *
     * @param TEngine The type of Ktor engine
     * @param TConfiguration The type of engine configuration
     * @param factory The Ktor engine factory (e.g., Netty, CIO, Jetty)
     *
     * Example:
     * ```kotlin
     * engine.start(Netty)
     * ```
     */
    public fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> start(factory: ApplicationEngineFactory<TEngine, TConfiguration>) {
        this.settings.ready()
        runBlocking { runStartupTasks() }
        val reliability = ktorRunConfig().reliability
        startSchedules(reliability.scheduleLockTtl)
        val drainMillis = reliability.shutdownDrainTimeout.inWholeMilliseconds
        val server = embeddedServer(
            factory = factory,
            port = ktorRunConfig().port,
            host = ktorRunConfig().host,
            module = { adapt() },
            watchPaths = listOf()
        )
        // 2.4: graceful shutdown on SIGTERM/SIGINT — stop accepting + drain in-flight, then
        // disconnect services. We block below to preserve the wait=true contract of the old call.
        registerShutdownHook {
            gracefulShutdown(reliability.shutdownDrainTimeout) {
                server.stop(gracePeriodMillis = drainMillis, timeoutMillis = drainMillis)
            }
        }
        server.start(wait = true)
    }

}


/**
 * Helper class for type-safe retrieval of values with serializers.
 * Wraps a retrieval function that takes a serializer and returns the corresponding value.
 */
@JvmInline
private value class TypeRetriever(val retriever: (KSerializer<*>) -> Any?) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> invoke(serializer: KSerializer<T>): T = retriever(serializer) as T

    companion object {
        fun of(retriever: (KSerializer<Nothing>) -> Nothing): TypeRetriever {
            @Suppress("UNCHECKED_CAST")
            return TypeRetriever(retriever as (KSerializer<*>) -> Any?)
        }

        fun literal(value: Any?) = TypeRetriever { value }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The start() method uses runBlocking which could block the calling thread unexpectedly.
 *    Consider documenting this behavior or providing a suspending alternative.
 * 2. The watchPaths parameter in embeddedServer is always empty - consider exposing this
 *    for development-time auto-reload functionality.
 * 3. The realIpHeader warning logs but doesn't fail - consider documenting the security
 *    implications of a missing real IP header when behind a proxy.
 * 4. Consider adding a graceful shutdown method that stops schedules and drains connections.
 * 5. The WebSocket path is extracted from query parameter with a "pathHack" - this seems
 *    like a workaround that should be documented or cleaned up.
 */
