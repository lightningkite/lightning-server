package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.engine.local.WsOversizePolicy
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.engine.local.LocalWebSocketConnection
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.*
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.data.DataSize.Companion.kibibytes
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.*
import io.netty.channel.*
import io.netty.channel.epoll.*
import io.netty.channel.kqueue.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpHeaderNames.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AttributeKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.KSerializer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.lightningkite.lightningserver.http.HttpHeaders as LsHttpHeaders
import com.lightningkite.lightningserver.websockets.WebSocketFrame as LkWebSocketFrame
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders

/**
 * A Lightning Server engine implementation using Netty's high-performance asynchronous I/O framework.
 *
 * This engine provides production-grade HTTP and WebSocket support with native transport optimizations:
 * - **Linux**: Uses epoll for optimal performance
 * - **macOS/BSD**: Uses kqueue for optimal performance
 * - **Other platforms**: Falls back to NIO
 *
 * **Features:**
 * - Full HTTP/1.1 support with keep-alive
 * - WebSocket support with pub/sub subscriptions
 * - Optional WebSocket compression (per-message deflate)
 * - Configurable worker thread pool
 * - Graceful shutdown support
 * - Real IP header support for proxy deployments
 * - Idle connection timeout (configurable via [NettyRuntimeSettings.reliability]; default 120 seconds)
 * - Per-request timeout (cooperative, default 30 seconds) and graceful SIGTERM drain
 *
 * **Performance characteristics:**
 * - Uses pooled byte buffer allocation for memory efficiency
 * - TCP_NODELAY enabled (disables Nagle's algorithm)
 * - SO_KEEPALIVE enabled for connection health checks
 * - Configurable buffer sizes and backlog
 * - Write buffer water marks: 32 KiB low, 64 KiB high
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
 *
 * @see NettyRuntimeSettings for configuration options
 * @see KtorEngine for an alternative production engine
 */
public class NettyEngine(
    server: ServerDefinition,
    override val clock: Clock = Clock.System,
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
    override lateinit var scope: CoroutineScope

    private lateinit var HANDSHAKER_KEY: AttributeKey<WebSocketServerHandshaker>
    private lateinit var MID_KEY: AttributeKey<WebSocketConnection<PathSpec, Any?>>
    private lateinit var PATHSPEC_KEY: AttributeKey<PathSpec>
    private lateinit var HANDLER_KEY: AttributeKey<WebSocketHandler<PathSpec, Any?>>
    private lateinit var DIRECT_CHANNEL_KEY: AttributeKey<SendChannel<LkWebSocketFrame>>

    /**
     * Starts the Netty HTTP server.
     *
     * This method:
     * 1. Ensures settings are ready and validated
     * 2. Runs any startup tasks defined in the server
     * 3. Starts the schedule coordinator
     * 4. Creates boss and worker event loop groups (with native transport if available)
     * 5. Configures the server bootstrap with performance optimizations
     * 6. Binds to the configured host and port
     * 7. Blocks indefinitely until the server shuts down
     *
     * **Transport selection:**
     * - Prefers epoll on Linux for best performance
     * - Falls back to kqueue on macOS/BSD
     * - Uses NIO as final fallback
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

        val useEpoll = Epoll.isAvailable()
        val useKQueue = KQueue.isAvailable()
        val boss: EventLoopGroup
        val worker: EventLoopGroup
        val channelClass: Class<out ServerChannel>
        val workerThreads = cfg.workerThreads ?: 0
        when {
            useEpoll -> {
                boss = EpollEventLoopGroup(1)
                worker = EpollEventLoopGroup(workerThreads)
                channelClass = EpollServerSocketChannel::class.java
            }

            useKQueue -> {
                boss = KQueueEventLoopGroup(1)
                worker = KQueueEventLoopGroup(workerThreads)
                channelClass = KQueueServerSocketChannel::class.java
            }

            else -> {
                boss = NioEventLoopGroup(1)
                worker = NioEventLoopGroup(workerThreads)
                channelClass = NioServerSocketChannel::class.java
            }
        }
        bossGroup = boss
        workerGroup = worker
        scope = CoroutineScope(worker.asCoroutineDispatcher() + supervisorJob)
        HANDSHAKER_KEY = AttributeKey.valueOf("HANDSHAKER")
        MID_KEY = AttributeKey.valueOf("MID")
        PATHSPEC_KEY = AttributeKey.valueOf("PATHSPEC")
        HANDLER_KEY = AttributeKey.valueOf("SOCKET_HANDLER")
        DIRECT_CHANNEL_KEY = AttributeKey.valueOf("DIRECT_CHANNEL")

        runBlocking { runStartupTasks() }
        startSchedules(cfg.reliability.scheduleLockTtl)

        val maxContentLength = cfg.maxAggregatedContentLength.bytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        if (cfg.maxAggregatedContentLength.bytes > Int.MAX_VALUE.toLong())
            logger.warn {
                "maxAggregatedContentLength: ${
                    cfg.maxAggregatedContentLength.gibibytes.times(100).toInt().div(100.0)
                } GiB is greater than the max supported size of ${
                    Int.MAX_VALUE.bytes.gibibytes.times(100).toInt().div(100.0)
                } GiB. Using max value instead."
            }

        val b = ServerBootstrap()
        b.group(boss, worker)
            .channel(channelClass)
            .option(ChannelOption.SO_BACKLOG, cfg.backlog.bytes.toInt())
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK,
                WriteBufferWaterMark(32.kibibytes.bytes.toInt(), 64.kibibytes.bytes.toInt())
            )
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.config().isAutoRead = cfg.autoRead
                    val p = ch.pipeline()
                    p.addLast(HttpServerCodec())
                    p.addLast(HttpServerExpectContinueHandler())
                    p.addLast(HttpObjectAggregator(maxContentLength))
                    p.addLast(ChunkedWriteHandler())
                    if (cfg.websocketCompression) p.addLast(WebSocketServerCompressionHandler())
                    // 2.3: idle-connection timeout (Netty-only). Closes connections with no read/write
                    // activity within reliability.idleTimeout.
                    p.addLast(IdleStateHandler(0, 0, cfg.reliability.idleTimeout.inWholeSeconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()))
                    p.addLast(NettyServerHandler(cfg))
                }
            })

        if (cfg.recvBufBytes != null) b.childOption(ChannelOption.SO_RCVBUF, cfg.recvBufBytes.bytes.toInt())
        if (cfg.sendBufBytes != null) b.childOption(ChannelOption.SO_SNDBUF, cfg.sendBufBytes.bytes.toInt())

        val ch = b.bind(cfg.host, cfg.port).sync().channel()
        val local = ch.localAddress() as? InetSocketAddress
        this@NettyEngine.boundAddress = local
        logger.info { "NettyEngine started on http://${cfg.host}:${local?.port ?: cfg.port}" }
        // 2.4: graceful shutdown on SIGTERM/SIGINT — drain in-flight requests then disconnect services.
        registerShutdownHook { shutdown() }
        ch.closeFuture().addListener { _ ->
            shutdown()
        }.sync()

    }

    /**
     * Initiates a graceful shutdown of the Netty server.
     *
     * Cancels schedules, stops accepting connections and drains in-flight requests by gracefully
     * shutting down the boss/worker event loop groups (bounded by
     * [EngineReliabilitySettings.shutdownDrainTimeout]), disconnects all services, then cancels the
     * engine scope. Idempotent — safe to call from both the SIGTERM hook and the channel close
     * listener.
     */
    public fun shutdown() {
        val drain = if (::scope.isInitialized) nettyRunConfig().reliability.shutdownDrainTimeout else null
        if (drain == null) {
            // Never started; nothing to drain.
            return
        }
        gracefulShutdown(drain) { timeout ->
            // shutdownGracefully drains in-flight work over its quiet/timeout window. We do NOT block
            // (.sync()) here: this drain may run on a Netty event-loop thread (via the channel
            // close-future listener), and waiting for the worker group to terminate from one of its
            // own threads would deadlock. The graceful window bounds the drain.
            try {
                val quietMillis = 0L
                val timeoutMillis = timeout.inWholeMilliseconds.coerceAtLeast(quietMillis)
                if (::bossGroup.isInitialized) {
                    bossGroup.shutdownGracefully(quietMillis, timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                if (::workerGroup.isInitialized) {
                    workerGroup.shutdownGracefully(quietMillis, timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private inner class NettyServerHandler(val cfg: NettyRuntimeSettings) : SimpleChannelInboundHandler<Any>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
            when (msg) {
                is FullHttpRequest -> {
                    val connection = msg.headers()[CONNECTION]?.lowercase() ?: ""
                    val upgrade = msg.headers()[UPGRADE]?.lowercase() ?: ""
                    if (connection.contains("upgrade") && upgrade == "websocket") {  // Is it a websocket?
                        scope.launch(ctx.executor().asCoroutineDispatcher()) {
                            handleWebSocketStartup(ctx, msg)
                        }
                    } else {
                        val request = msg.toLightningHttpRequest(ctx, cfg)
                        scope.launch(ctx.executor().asCoroutineDispatcher()) {
                            try {
                                try {
                                    // Request timeout is enforced centrally in ServerRuntime.handle (per-handler HttpHandler.timeout).
                                    val result: HttpResponse = this@NettyEngine.handle(request)
                                    val nettyRes = result.toNettyResponse(msg.protocolVersion())
                                    val keepAlive = HttpUtil.isKeepAlive(msg)
                                    if (keepAlive) {
                                        nettyRes.headers()[CONNECTION] = HttpHeaderValues.KEEP_ALIVE
                                        ctx.writeAndFlush(nettyRes)
                                    } else {
                                        ctx.writeAndFlush(nettyRes).addListener(ChannelFutureListener.CLOSE)
                                    }
                                } finally {
                                    request.body?.close()
                                }
                            } catch (e: Throwable) {
                                logger.error(e) { "Netty error!" }
                                try {
                                    val body = "Internal Server Error"
                                    val res = DefaultFullHttpResponse(
                                        msg.protocolVersion(),
                                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                        Unpooled.wrappedBuffer(body.toByteArray())
                                    )
                                    res.headers()[CONTENT_TYPE] = "text/plain; charset=utf-8"
                                    res.headers()[CONTENT_LENGTH] = body.toByteArray().size.toString()
                                    val keepAlive = HttpUtil.isKeepAlive(msg)
                                    if (keepAlive) {
                                        res.headers()[CONNECTION] = HttpHeaderValues.KEEP_ALIVE
                                        ctx.writeAndFlush(res)
                                    } else {
                                        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }

                is TextWebSocketFrame -> {
                    val directChannel = ctx.channel().attr(DIRECT_CHANNEL_KEY).get()
                    val m = LkWebSocketFrame(msg.text())
                    if (directChannel != null) {
                        deliverDirect(ctx, directChannel, m)
                    } else {
                        // Standard pub/sub mode
                        val mid = ctx.channel().attr(MID_KEY).get() ?: return
                        val handler = ctx.channel().attr(HANDLER_KEY).get() ?: return
                        val pathspec = ctx.channel().attr(PATHSPEC_KEY).get() ?: return
                        scope.launch(ctx.executor().asCoroutineDispatcher()) {
                            try {
                                handler.messageFromClientWithMetrics(pathspec, mid, m)
                            } catch (e: Exception) {
                                mid.close(
                                    ((e as? HttpStatusException)?.status
                                        ?: HttpStatus.InternalServerError).bestWebsocketCloseCode
                                )
                            }
                        }
                    }
                }

                is BinaryWebSocketFrame -> {
                    val directChannel = ctx.channel().attr(DIRECT_CHANNEL_KEY).get()
                    val bytes = ByteBufUtil.getBytes(msg.content())
                    val m = LkWebSocketFrame(bytes)
                    if (directChannel != null) {
                        deliverDirect(ctx, directChannel, m)
                    } else {
                        // Standard pub/sub mode
                        val mid = ctx.channel().attr(MID_KEY).get() ?: return
                        val handler = ctx.channel().attr(HANDLER_KEY).get() ?: return
                        val pathspec = ctx.channel().attr(PATHSPEC_KEY).get() ?: return
                        scope.launch(ctx.executor().asCoroutineDispatcher()) {
                            try {
                                handler.messageFromClientWithMetrics(pathspec, mid, m)
                            } catch (e: Exception) {
                                mid.close(
                                    ((e as? HttpStatusException)?.status
                                        ?: HttpStatus.InternalServerError).bestWebsocketCloseCode
                                )
                            }
                        }
                    }
                }

                is CloseWebSocketFrame -> {
                    val directChannel = ctx.channel().attr(DIRECT_CHANNEL_KEY).get()
                    if (directChannel != null) {
                        // Direct mode - close the channel
                        directChannel.close()
                    } else {
                        // Standard pub/sub mode
                        val mid = ctx.channel().attr(MID_KEY).get()
                        val handler = ctx.channel().attr(HANDLER_KEY).get()
                        val pathspec = ctx.channel().attr(PATHSPEC_KEY).get()
                        if (mid != null && handler != null && pathspec != null) {
                            scope.launch(ctx.executor().asCoroutineDispatcher()) {
                                try {
                                    handler.disconnectWithMetrics(pathspec, mid, WebSocketClose.NORMAL)
                                } catch (e: Exception) {
                                    mid.close(
                                        ((e as? HttpStatusException)?.status
                                            ?: HttpStatus.InternalServerError).bestWebsocketCloseCode
                                    )
                                }
                            }
                        }
                    }
                    ctx.close()
                }

                is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(msg.content().retain()))
                is PongWebSocketFrame -> { /* ignore */
                }

                else -> { /* ignore */
                }
            }
        }

        /**
         * 2.10: delivers an inbound WebSocket frame to the direct handler's bounded channel,
         * applying [EngineReliabilitySettings.webSocketOversizePolicy] on overflow. For
         * [WsOversizePolicy.CLOSE] a full buffer means the peer is outrunning the handler, so the
         * socket is closed with code 1009 (message too big). DROP_OLDEST and SUSPEND are handled by
         * the channel's own BufferOverflow policy via a (possibly suspending) send.
         */
        private fun deliverDirect(
            ctx: ChannelHandlerContext,
            directChannel: SendChannel<LkWebSocketFrame>,
            frame: LkWebSocketFrame,
        ) {
            if (cfg.reliability.webSocketOversizePolicy == WsOversizePolicy.CLOSE) {
                val result = directChannel.trySend(frame)
                if (result.isFailure && !result.isClosed) {
                    ctx.writeAndFlush(
                        CloseWebSocketFrame(WebSocketClose.TOO_BIG.code.toInt(), "WebSocket inbound buffer overflow")
                    ).addListener(ChannelFutureListener.CLOSE)
                }
            } else {
                scope.launch(ctx.executor().asCoroutineDispatcher()) {
                    try {
                        directChannel.send(frame)
                    } catch (_: Exception) {
                        // Channel closed
                    }
                }
            }
        }

        private suspend fun handleWebSocketStartup(ctx: ChannelHandlerContext, req: FullHttpRequest) {
            val wsRequest = try {
                req.toLightningWebSocketConnectRequest(ctx, cfg)
            } catch (e: Throwable) {
                logger.error(e) { "" }
                val res = DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
                ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
                return
            }
            val match = this@NettyEngine.server.endpoints.match(
                this@NettyEngine.externalSerialization.stringArrayFormat,
                wsRequest.path.pathSegments
            ) { it.websocket }
                ?: run {
                    val exception = NotFoundException("No websocket at '${wsRequest.path}'")
                    logger.error(exception) { "" }
                    val res = DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.NOT_FOUND)
                    ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
                    return
                }

            val socketHandler = this@NettyEngine.server.compiledWebsocketInterceptors.intercept(match.value)

            val host = req.headers()[HOST] ?: "localhost"
            // Netty's own default payload limit is 64 KiB; honour the configured one so both engines
            // bound this peer-driven allocation the same way.
            val wsFactory = WebSocketServerHandshakerFactory(
                "ws://$host${URI(req.uri()).path}",
                null,
                true,
                websocketSettings().maxFrameSize?.bytes?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                    ?: Int.MAX_VALUE,
            )
            val handshaker = wsFactory.newHandshaker(req)
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
                return
            }
            ctx.channel().attr(HANDSHAKER_KEY).set(handshaker)

            // Check for direct execution capability - bypasses pub/sub overhead
            if (socketHandler is DirectExecutableWebSocketHandler<*> && !forceWebSocketPubSub()) {
                @Suppress("UNCHECKED_CAST")
                val directHandler = socketHandler as DirectExecutableWebSocketHandler<PathSpec>

                // 2.10: bounded inbound channel with backpressure instead of Channel.UNLIMITED.
                val incomingChannel = newWebSocketInboundChannel<LkWebSocketFrame>(cfg.reliability)
                ctx.channel().attr(DIRECT_CHANNEL_KEY).set(incomingChannel)

                // Complete handshake and then run direct handler
                handshaker.handshake(ctx.channel(), req).addListener {
                    scope.launch {
                        try {
                            directHandler.handleDirect(
                                serverRuntime = this@NettyEngine,
                                request = wsRequest,
                                incoming = incomingChannel,
                                send = { frame ->
                                    when (frame) {
                                        is LkWebSocketFrame.Binary -> ctx.writeAndFlush(
                                            BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame.content))
                                        )

                                        is LkWebSocketFrame.Text -> ctx.writeAndFlush(TextWebSocketFrame(frame.content))
                                    }
                                },
                                close = { reason ->
                                    ctx.writeAndFlush(CloseWebSocketFrame(reason.code.toInt(), reason.name))
                                        .addListener(ChannelFutureListener.CLOSE)
                                }
                            )
                        } catch (e: Throwable) {
                            logger.error(e) { "Direct WebSocket handler failed" }
                            ctx.close()
                        }
                    }
                }
            } else {
                // Standard pub/sub-based implementation
                @Suppress("UNCHECKED_CAST")
                socketHandler as WebSocketHandler<PathSpec, Any?>

                val startingState = try {
                    socketHandler.willConnectWithMetrics(match.pathSpec, this@NettyEngine, wsRequest)
                } catch (e: HttpStatusException) {
                    logger.error(e) { "" }
                    val res = DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.valueOf(e.status.code))
                    ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
                    return
                } catch (e: Throwable) {
                    logger.error(e) { "" }
                    val res = DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
                    ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE)
                    return
                }

                val mid = object : LocalWebSocketConnection<PathSpec, Any?>(
                    startingState = startingState,
                    request = wsRequest,
                    handler = socketHandler,
                    scope = CoroutineScope(Dispatchers.IO),
                    server = this@NettyEngine,
                    pubSub = { this@NettyEngine.pubSubChannel(it) }
                ) {
                    override suspend fun send(frame: LkWebSocketFrame) {
                        when (frame) {
                            is LkWebSocketFrame.Binary -> ctx.writeAndFlush(
                                BinaryWebSocketFrame(
                                    Unpooled.wrappedBuffer(
                                        frame.content
                                    )
                                )
                            )

                            is LkWebSocketFrame.Text -> ctx.writeAndFlush(TextWebSocketFrame(frame.content))
                        }
                    }

                    override suspend fun close(reason: WebSocketClose) {
                        val hs = ctx.channel().attr(HANDSHAKER_KEY).get()
                        if (hs != null) {
                            ctx.writeAndFlush(CloseWebSocketFrame(reason.code.toInt(), reason.name))
                                .addListener(ChannelFutureListener.CLOSE)
                        } else {
                            ctx.close()
                        }
                    }
                }

                ctx.channel().attr(MID_KEY).set(mid)
                ctx.channel().attr(HANDLER_KEY).set(socketHandler)
                ctx.channel().attr(PATHSPEC_KEY).set(match.pathSpec)

                handshaker.handshake(ctx.channel(), req).addListener {
                    scope.launch {
                        try {
                            socketHandler.didConnectWithMetrics(match.pathSpec, mid)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }


        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                ctx.close()
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val directChannel = ctx.channel().attr(DIRECT_CHANNEL_KEY).get()
            if (directChannel != null) {
                // Direct mode - close the channel
                directChannel.close()
            } else {
                // Standard pub/sub mode
                val mid = ctx.channel().attr(MID_KEY).get()
                val handler = ctx.channel().attr(HANDLER_KEY).get()
                if (mid != null && handler != null) {
                    try {
                        context(mid) {
                            scope.launch {
                                logger.error { "Disconnected because channel is inactive " }
                                handler.disconnect(WebSocketClose.GOING_AWAY)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            super.channelInactive(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            try {
                ctx.close()
            } catch (_: Throwable) {
            }
        }

        private fun FullHttpRequest.toLightningHttpRequest(
            ctx: ChannelHandlerContext,
            cfg: NettyRuntimeSettings,
        ): HttpRequest<PathSpec> {

            val parts = QueryStringDecoder(this.uri())
            val headers = (this.headers() as NettyHttpHeaders).toLightningHeaders()
            val contentTypeHeader = headers.contentType

            val body = if (this.content().isReadable) {
                val bytes = ByteArray(this.content().readableBytes())
                this.content().getBytes(this.content().readerIndex(), bytes)
                TypedData(
                    data = Data.Bytes(bytes),
                    mediaType = contentTypeHeader ?: MediaType.Application.OctetStream,
                )
            } else null

            val hostHeader = this.headers()[HOST] ?: ""
            val domain = hostHeader.substringBefore(":")
            val sourceIp = cfg.realIpHeader?.let { h ->
                this.headers()[h] ?: run {
                    logger.warn { "Real IP address header for proxy '$h' was missing from the request." }
                    null
                }
            } ?: ((ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "")

            val identity = headers.requestIdentity(cfg.requestIdHeader) {
                logger.warn { "Request ID header for proxy '${cfg.requestIdHeader}' was missing from the request." }
            }

            return HttpRequest(
                path = RawHttpEndpoint(parts.path(), HttpMethod(this.method().name())),
                queryParameters = QueryParameters(
                    parts.parameters().flatMap { (key, values) -> values.map { key to it } }),
                headers = headers,
                domain = domain.ifEmpty { (ctx.channel().localAddress() as? InetSocketAddress)?.hostString.orEmpty() },
                protocol = "http",
                sourceIp = sourceIp,
                requestId = identity.requestId,
                upstreamRequestId = identity.upstreamRequestId,
                body = body,
            )
        }

        private fun FullHttpRequest.toLightningWebSocketConnectRequest(
            ctx: ChannelHandlerContext,
            cfg: NettyRuntimeSettings,
        ): WebSocketConnectRequest<PathSpec> {
            val parts = QueryStringDecoder(this.uri())
            val headers = (this.headers() as NettyHttpHeaders).toLightningHeaders()
            val hostHeader = this.headers()[HOST] ?: ""
            val domain = hostHeader.substringBefore(":")
            val sourceIp = cfg.realIpHeader?.let { h ->
                this.headers()[h] ?: run {
                    logger.warn { "Real IP address header for proxy '$h' was missing from the request." }
                    null
                }
            } ?: ((ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "")

            val identity = headers.requestIdentity(cfg.requestIdHeader) {
                logger.warn { "Request ID header for proxy '${cfg.requestIdHeader}' was missing from the request." }
            }

            return WebSocketConnectRequest(
                path = RawWebsocketPath(parts.path()),
                queryParameters = QueryParameters(
                    parts.parameters().flatMap { (key, values) -> values.map { key to it } }),
                headers = headers,
                domain = domain.ifEmpty { (ctx.channel().localAddress() as? InetSocketAddress)?.hostString.orEmpty() },
                protocol = "http",
                sourceIp = sourceIp,
                requestId = identity.requestId,
                upstreamRequestId = identity.upstreamRequestId,
            )
        }

        private suspend fun HttpResponse.toNettyResponse(version: HttpVersion): FullHttpResponse {
            val contentBuf = this.body?.data?.bytes()
                ?.let { Unpooled.wrappedBuffer(it) }
                ?: Unpooled.EMPTY_BUFFER

            val res = DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(this.status.code), contentBuf)

            for ((key, values) in this.headers.normalizedEntries) {
                for (value in values) {
                    res.headers().add(key, value.toHttpString())
                }
            }
            this.body?.mediaType?.let { mt ->
                res.headers()[CONTENT_TYPE] = mt.toString()
            }
            // Always advertise the body length, including 0 for bodyless responses (redirects, etc.).
            // Without a Content-Length (or 0) a keep-alive HTTP/1.1 client cannot tell the response is
            // complete and stalls until the idle timeout closes the connection — a cross-engine
            // conformance defect caught by EngineHttpConformanceSuite.trailing_slash_redirects_307.
            res.headers()[CONTENT_LENGTH] = contentBuf.readableBytes().toString()

            return res
        }

        private fun NettyHttpHeaders.toLightningHeaders(): LsHttpHeaders =
            HttpHeaders(this@toLightningHeaders.entries().map { Pair(it.key, it.value) })

    }


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
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider extracting magic numbers to named constants:
 *    - Idle timeout (120 seconds, line 156)
 *    - Write buffer water marks (32 KiB / 64 KiB, line 145)
 *    - Boss thread count (1, line 96/102/108)
 *
 * 2. The toLightningHeaders() function splits comma-separated headers, but some headers (like Set-Cookie)
 *    shouldn't be split. Consider header-specific handling.
 *
 * 3. Consider adding metrics/telemetry for:
 *    - Active connection count
 *    - Request throughput
 *    - WebSocket connection count
 *    - Event loop queue depth
 *
 * 4. The toNettyResponse() function loads the entire response body into memory (line 489).
 *    Consider streaming support for large responses.
 *
 * 5. Consider making the idle timeout configurable via NettyRuntimeSettings instead of hardcoding to 120 seconds.
 *
 * 6. The error handling for WebSocket operations catches and ignores Throwables silently (line 387, 413).
 *    Consider adding logging or metrics for these failures.
 *
 * 7. Consider documenting the thread safety characteristics of currentState in LocalWebSocketConnection,
 *    as modifications are not synchronized.
 *
 * 8. The TypeRetriever class at the end appears unused in this file. Consider removing if not referenced elsewhere.
 */

