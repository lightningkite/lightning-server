@file:OptIn(InternalLightningServerApi::class, EngineApi::class)

package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketSettings
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.lightningserver.websockets.webSocketSettings
import com.lightningkite.services.data.DataSize.Companion.kibibytes
import com.lightningkite.services.pubsub.PubSubChannel
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.epoll.*
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.ChannelMatcher
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.kqueue.*
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import io.netty.handler.flow.FlowControlHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.GlobalEventExecutor
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel as CoroutineChannel

// Names of the pipeline's handlers that are looked up again after they are added.
internal const val IDLE_HANDLER = "idle"
internal const val FLOW_HANDLER = "flow"
internal const val HTTP_HANDLER = "http"
internal const val WS_AGGREGATOR = "ws-aggregator"
internal const val WS_HANDLER = "ws"

/** Number of threads accepting connections. One is the norm: accepting is cheap. */
private const val BOSS_THREADS = 1

/**
 * How long shutdown waits for connections to finish closing even when its drain window is spent,
 * so the disconnects that closing raises are queued before shutdown moves on.
 */
private const val MIN_CLOSE_WAIT_MILLIS = 1_000L

/**
 * A Lightning Server engine implementation using Netty's high-performance asynchronous I/O framework.
 *
 * **Transports:** epoll on Linux and kqueue on macOS/BSD are used when their native libraries are on
 * the classpath; otherwise the engine runs on NIO. This module ships only the transport *classes*, so
 * an application that wants a native transport adds the matching artifact itself, for example
 * `io.netty:netty-transport-native-epoll` with the `linux-x86_64` classifier. The transport in use is
 * logged at startup.
 *
 * **Features:**
 * - Full HTTP/1.1 support with keep-alive
 * - Streamed request and response bodies: neither is held in memory whole unless the handler asks
 *   for the bytes. Request size is capped by [EngineReliabilitySettings.maxBodySize].
 * - WebSocket support with pub/sub subscriptions, fragmented messages, and a proper close handshake
 * - Server-side WebSocket pings (see [WebSocketSettings])
 * - Optional WebSocket compression (per-message deflate)
 * - Configurable worker thread pool
 * - Graceful shutdown support
 * - Real IP header support for proxy deployments
 * - Idle connection timeout (configurable via [NettyRuntimeSettings.reliability]; default 120 seconds)
 * - Per-request timeout (cooperative, default 30 seconds) and graceful SIGTERM drain
 *
 * **Performance characteristics:**
 * - TCP_NODELAY enabled (disables Nagle's algorithm)
 * - SO_KEEPALIVE enabled for connection health checks
 * - Configurable buffer sizes and backlog
 * - Write buffer water marks: 32 KiB low, 64 KiB high. A WebSocket `send` suspends while the
 *   connection is above the high mark, so a slow client slows its sender instead of growing a buffer.
 *
 * **Threading:** Netty's event loops only move bytes. Handlers, tasks and schedules run on
 * [Dispatchers.Default], so a handler that blocks ties up one of those threads but never stops other
 * connections from being read, written, or pinged.
 *
 * **Usage:**
 * ```kotlin
 * val engine = NettyEngine(serverDefinition)
 * engine.settings.loadFromFile(KFile("settings.json"), serializersModule)
 * engine.start() // Blocks until shutdown
 * ```
 *
 * @param server The server definition to run
 * @param clock The clock to use for timing operations (defaults to System clock)
 * @param disableResponseStreaming Return true for a request whose response must be sent in one piece
 *      with a known `Content-Length` instead of streamed. Useful for clients, such as memory-limited
 *      IoT devices, that cannot handle a chunked response.
 *
 * @see NettyRuntimeSettings for configuration options
 * @see KtorEngine for an alternative production engine
 */
public class NettyEngine(
    server: ServerDefinition,
    override val clock: Clock = Clock.System,
    internal val disableResponseStreaming: (HttpRequest<*>) -> Boolean = { false },
) : LocalEngine(server) {

    public companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.lightningserver.engine.netty.NettyEngine")
    }

    override val settings: ServerSettings = super.settings + nettyRunConfig

    private lateinit var bossGroup: EventLoopGroup
    private lateinit var workerGroup: EventLoopGroup

    /**
     * The bound address after the server starts, or null if not yet started.
     * Useful for determining the actual port when binding to port 0 (random port).
     */
    @Volatile
    public var boundAddress: InetSocketAddress? = null

    private val supervisorJob = SupervisorJob()

    /**
     * Where handlers, tasks and schedules run. Deliberately not the Netty worker group: application
     * code may block, and an event loop that is blocked stops serving every connection assigned to it.
     */
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)

    @Volatile
    private var started = false

    /**
     * Every accepted connection, so shutdown can close them itself rather than hoping they happen to
     * close in time.
     *
     * `shutdownGracefully` is deliberately not awaited (see [shutdown]), so without this the drain
     * returns while sockets are still open: their `channelInactive` — and therefore their disconnect
     * phase — would be raised after the point where that work is still waited on. Closing the group
     * explicitly makes the ordering deterministic instead of a race. Netty removes channels from the
     * group as they close.
     */
    private val openChannels: ChannelGroup = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

    /** The listening socket, so shutdown can stop accepting before it drains. */
    @Volatile
    private var serverChannel: Channel? = null

    /**
     * Set once shutdown begins. From then on no connection is accepted, no response offers
     * keep-alive, and every connection closes once its current response is out.
     */
    @Volatile
    internal var draining: Boolean = false
        private set

    /**
     * HTTP handlers still running, so shutdown can wait for them. Their coroutines run on [scope],
     * not on the event loops, so shutting the loops down does not wait for them.
     */
    internal val inFlight: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /**
     * Every pub/sub socket that has not yet disconnected, so shutdown can raise the disconnects
     * itself (see [shutdown]).
     */
    internal val pubSubSessions: MutableSet<PubSubSession> = ConcurrentHashMap.newKeySet()

    // LocalEngine keeps these protected. The connection handlers are classes of their own, and
    // reach them through here.
    internal val handlerScope: CoroutineScope get() = scope
    internal val disconnectScope: CoroutineScope get() = cleanupScope

    internal fun <T> inboundChannel(reliability: EngineReliabilitySettings): CoroutineChannel<T> =
        newWebSocketInboundChannel(reliability)

    internal fun subscriptionChannel(request: WebSocketSubscriptionRequest<*, Any?>): PubSubChannel<Any?> =
        pubSubChannel(request)

    /**
     * Starts the Netty HTTP server.
     *
     * This method:
     * 1. Ensures settings are ready and validated
     * 2. Runs any startup tasks defined in the server
     * 3. Starts the schedule coordinator
     * 4. Creates boss and worker event loop groups (with a native transport if available)
     * 5. Configures the server bootstrap
     * 6. Binds to the configured host and port
     * 7. Blocks indefinitely until the server shuts down
     *
     * **Thread configuration:**
     * - Boss group: 1 thread (handles accept operations)
     * - Worker group: Configurable via [NettyRuntimeSettings.workerThreads] (handles I/O and request processing)
     *
     * Note: This method blocks the calling thread.
     */
    public fun start() {
        // Prepare configuration and lifecycle
        this.settings.ready()

        val cfg = nettyRunConfig()

        val (transport, ioHandlerFactory, channelClass) = when {
            Epoll.isAvailable() -> Triple("epoll", EpollIoHandler.newFactory(), EpollServerSocketChannel::class.java)
            KQueue.isAvailable() -> Triple("kqueue", KQueueIoHandler.newFactory(), KQueueServerSocketChannel::class.java)
            else -> Triple("nio", NioIoHandler.newFactory(), NioServerSocketChannel::class.java)
        }
        bossGroup = MultiThreadIoEventLoopGroup(BOSS_THREADS, ioHandlerFactory)
        workerGroup = MultiThreadIoEventLoopGroup(cfg.workerThreads ?: 0, ioHandlerFactory)
        started = true

        val ch = try {
            bind(cfg, channelClass)
        } catch (e: Throwable) {
            // Startup tasks, schedules and the event loops are all running by now; leave nothing behind.
            shutdown()
            throw e
        }
        val local = ch.localAddress() as? InetSocketAddress
        boundAddress = local
        logger.info { "NettyEngine started on http://${cfg.host}:${local?.port ?: cfg.port} ($transport transport)" }
        // 2.4: graceful shutdown on SIGTERM/SIGINT — drain in-flight requests then disconnect services.
        registerShutdownHook { shutdown() }
        ch.closeFuture().addListener { _ ->
            shutdown()
        }.sync()

    }

    /** Runs the startup tasks and schedules, then opens the listening socket. */
    private fun bind(cfg: NettyRuntimeSettings, channelClass: Class<out ServerChannel>): Channel {
        runBlocking { runStartupTasks() }
        startSchedules(cfg.reliability.scheduleLockTtl)

        val maxBody = cfg.reliability.maxBodySize.bytes
        val maxMessage = webSocketMaxMessageSize()

        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
            .channel(channelClass)
            .option(ChannelOption.SO_BACKLOG, cfg.backlog)
            .option(ChannelOption.SO_REUSEADDR, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK,
                WriteBufferWaterMark(32.kibibytes.bytes.toInt(), 64.kibibytes.bytes.toInt())
            )
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Accepted in the moment before the listening socket closed.
                    if (draining) {
                        ch.close()
                        return
                    }
                    openChannels.add(ch)
                    val p = ch.pipeline()
                    p.addLast(HttpServerCodec())
                    p.addLast(BodyLimitExpectContinueHandler(maxBody))
                    // Bounds what one compressed frame may inflate to. Without it a small frame can
                    // decompress to gigabytes before any size check sees it.
                    if (cfg.webSocketCompression) p.addLast(WebSocketServerCompressionHandler(maxMessage))
                    // 2.3: idle-connection timeout (Netty-only). Closes connections with no read/write
                    // activity within reliability.idleTimeout. A WebSocket with pings enabled swaps this
                    // for a ping-based liveness check when it upgrades.
                    p.addLast(
                        IDLE_HANDLER,
                        IdleStateHandler(0, 0, cfg.reliability.idleTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    )
                    // The decoder passes on everything it finds in a read, wanted or not. This holds
                    // those messages back while the HTTP handler has reading switched off, which is
                    // what lets a slow consumer of a request body hold the client back instead of
                    // buffering the upload, and keeps a second request waiting for the first's response.
                    p.addLast(FLOW_HANDLER, FlowControlHandler())
                    p.addLast(HTTP_HANDLER, HttpChannelHandler(this@NettyEngine, cfg, maxBody))
                }
            })

        if (cfg.recvBufBytes != null) b.childOption(ChannelOption.SO_RCVBUF, cfg.recvBufBytes.bytes.toInt())
        if (cfg.sendBufBytes != null) b.childOption(ChannelOption.SO_SNDBUF, cfg.sendBufBytes.bytes.toInt())

        val ch = b.bind(cfg.host, cfg.port).sync().channel()
        serverChannel = ch
        return ch
    }

    /**
     * The largest WebSocket message accepted, whole or reassembled from fragments, and the most one
     * compressed frame may inflate to. From [WebSocketSettings.maxFrameSize], clamped to what a
     * Netty buffer can hold.
     */
    internal fun webSocketMaxMessageSize(): Int =
        webSocketSettings().maxFrameSize?.bytes?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE

    /**
     * Initiates a graceful shutdown of the Netty server.
     *
     * Within [EngineReliabilitySettings.shutdownDrainTimeout]:
     * 1. Stops accepting connections, and closes the ones that are between requests.
     * 2. Waits for the HTTP handlers still running. Their responses go out with `Connection: close`,
     *    and each connection closes once its response has been written.
     * 3. Tells pub/sub WebSockets the server is going away, then closes every connection still open.
     * 4. Shuts down the event loop groups.
     *
     * [gracefulShutdown] then waits for the socket disconnects this raised, disconnects all services,
     * and cancels the engine scope. Idempotent: safe to call from both the SIGTERM hook and the
     * channel close listener.
     */
    public fun shutdown() {
        // Never started; nothing to drain.
        if (!started) return
        val drain = nettyRunConfig().reliability.shutdownDrainTimeout
        gracefulShutdown(drain) { timeout ->
            // Nothing here waits on the event loops' own futures: this may run on the boss loop (via
            // the server channel's close listener), and waiting for a group to terminate from one of
            // its own threads would deadlock. Handler jobs run on Dispatchers.Default and the channel
            // group's futures belong to GlobalEventExecutor, so waiting on those is safe.
            try {
                val deadline = TimeSource.Monotonic.markNow() + timeout
                fun remainingMillis() = (-deadline.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0)

                draining = true
                serverChannel?.close()
                for (channel in openChannels) {
                    val handler = channel.pipeline().get(HttpChannelHandler::class.java) ?: continue
                    try {
                        channel.eventLoop().execute { handler.closeIfIdle(channel) }
                    } catch (_: RejectedExecutionException) {
                        // The loop is already going, and the connection with it.
                    }
                }

                // Each handler queues its response before it finishes, so once these are done every
                // response is on its way.
                runBlocking {
                    withTimeoutOrNull(remainingMillis()) {
                        while (true) {
                            val pending = inFlight.toList()
                            if (pending.isEmpty()) break
                            pending.joinAll()
                        }
                    }
                }
                // Then give those responses time to leave: their connections close once written.
                openChannels.newCloseFuture(ChannelMatcher { it.pipeline().get(HttpChannelHandler::class.java) != null })
                    .await(remainingMillis(), TimeUnit.MILLISECONDS)

                // Close what is left (WebSockets, and anything that outlasted the window), and wait
                // for it. Closing a socket is what raises its disconnect phase, so this has to finish
                // before gracefulShutdown moves on to draining that work, otherwise the disconnects
                // are still being queued when it looks for them and finds nothing. Allowed a moment
                // even when the window is spent, for the same reason.
                pubSubSessions.toList().forEach { it.disconnect(WebSocketClose.GOING_AWAY) }
                openChannels.close().await(remainingMillis().coerceAtLeast(MIN_CLOSE_WAIT_MILLIS), TimeUnit.MILLISECONDS)

                // Both groups exist by the time `started` is set. No quiet period: everything is closed.
                val groupTimeoutMillis = remainingMillis()
                bossGroup.shutdownGracefully(0, groupTimeoutMillis, TimeUnit.MILLISECONDS)
                workerGroup.shutdownGracefully(0, groupTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                logger.warn(e) { "Error while closing connections during shutdown." }
            }
        }
    }
}
