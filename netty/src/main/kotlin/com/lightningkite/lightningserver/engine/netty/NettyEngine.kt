package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.engine.local.LocalEngine
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.PathAndParams
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebsocketPath
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerSocketChannel
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
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.KSerializer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.time.Clock
import com.lightningkite.lightningserver.http.HttpHeaders as LsHttpHeaders
import com.lightningkite.lightningserver.websockets.WebSocketFrame as LkWebSocketFrame
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders


public class NettyEngine(
    server: ServerDefinition,
    override val clock: Clock = Clock.System,
) : LocalEngine(server) {

    public companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.lightningserver.engine.netty.NettyEngine")
    }

    override val settings: ServerSettings = ServerSettings(super.settings.settings.plus(nettyRunConfig).toSet())

    private lateinit var bossGroup: EventLoopGroup
    private lateinit var workerGroup: EventLoopGroup

    @Volatile
    public var boundAddress: InetSocketAddress? = null

    private val supervisorJob = SupervisorJob()
    override lateinit var scope: CoroutineScope

    private lateinit var HANDSHAKER_KEY: AttributeKey<WebSocketServerHandshaker>
    private lateinit var MID_KEY: AttributeKey<WebSocketConnection<PathSpec, Any?>>
    private lateinit var PATHSPEC_KEY: AttributeKey<PathSpec>
    private lateinit var HANDLER_KEY: AttributeKey<WebSocketHandler<PathSpec, Any?>>

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

        runBlocking { runStartupTasks() }
        startSchedules()

        val b = ServerBootstrap()
        b.group(boss, worker)
            .channel(channelClass)
            .option(ChannelOption.SO_BACKLOG, cfg.backlog)
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(32 * 1024, 64 * 1024))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.config().isAutoRead = cfg.autoRead
                    val p = ch.pipeline()
                    p.addLast(HttpServerCodec())
                    p.addLast(HttpServerExpectContinueHandler())
                    p.addLast(HttpObjectAggregator(cfg.maxAggregatedContentLengthBytes))
                    p.addLast(ChunkedWriteHandler())
                    if (cfg.websocketCompression) p.addLast(WebSocketServerCompressionHandler())
                    p.addLast(IdleStateHandler(0, 0, 120))
                    p.addLast(NettyServerHandler(cfg))
                }
            })

        if (cfg.recvBufBytes != null) b.childOption(ChannelOption.SO_RCVBUF, cfg.recvBufBytes)
        if (cfg.sendBufBytes != null) b.childOption(ChannelOption.SO_SNDBUF, cfg.sendBufBytes)

        val ch = b.bind(cfg.host, cfg.port).sync().channel()
        val local = ch.localAddress() as? java.net.InetSocketAddress
        this@NettyEngine.boundAddress = local
        logger.info { "NettyEngine started on http://${cfg.host}:${local?.port ?: cfg.port}" }
        ch.closeFuture().addListener { _ ->
            shutdown()
        }
    }

    public fun shutdown() {
        try {
            workerGroup?.shutdownGracefully()
            bossGroup?.shutdownGracefully()
        } catch (_: Throwable) {
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
                                    val result: HttpResponse = this@NettyEngine.handle(request)
                                    val nettyRes = result.toNettyResponse(msg.protocolVersion())
                                    val keepAlive = HttpUtil.isKeepAlive(msg)
                                    if (keepAlive) {
                                        nettyRes.headers()[HttpHeaderNames.CONNECTION] = HttpHeaderValues.KEEP_ALIVE
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
                                    res.headers()[HttpHeaderNames.CONTENT_TYPE] = "text/plain; charset=utf-8"
                                    res.headers()[HttpHeaderNames.CONTENT_LENGTH] = body.toByteArray().size.toString()
                                    val keepAlive = HttpUtil.isKeepAlive(msg)
                                    if (keepAlive) {
                                        res.headers()[HttpHeaderNames.CONNECTION] = HttpHeaderValues.KEEP_ALIVE
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
                    val mid = ctx.channel().attr(MID_KEY).get() ?: return
                    val handler = ctx.channel().attr(HANDLER_KEY).get() ?: return
                    val pathspec = ctx.channel().attr(PATHSPEC_KEY).get() ?: return
                    val m = LkWebSocketFrame(msg.text())
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

                is BinaryWebSocketFrame -> {
                    val mid = ctx.channel().attr(MID_KEY).get() ?: return
                    val handler = ctx.channel().attr(HANDLER_KEY).get() ?: return
                    val pathspec = ctx.channel().attr(PATHSPEC_KEY).get() ?: return
                    val bytes = ByteBufUtil.getBytes(msg.content())
                    val m = LkWebSocketFrame(bytes)
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

                is CloseWebSocketFrame -> {
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
                    ctx.close()
                }

                is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(msg.content().retain()))
                is PongWebSocketFrame -> { /* ignore */
                }

                else -> { /* ignore */
                }
            }
        }

        private suspend fun handleWebSocketStartup(ctx: ChannelHandlerContext, req: FullHttpRequest) {
            val wsRequest = req.toLightningWebSocketConnectRequest(ctx, cfg)

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

            @Suppress("UNCHECKED_CAST")
            val socketHandler =
                this@NettyEngine.server.compiledWebsocketInterceptors(match.value) as WebSocketHandler<PathSpec, Any?>

            val startingState = try {
                socketHandler.willConnectWithMetrics(match.pathSpec, this@NettyEngine, wsRequest)
            } catch (e: HttpStatusException) {
//                val code = when (e.status.code / 100) { 1,2,3 -> WebSocketClose.NORMAL; 4 -> WebSocketClose.CLOSED_ABNORMALLY; else -> WebSocketClose.INTERNAL_ERROR }
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

            val host = req.headers()[HOST] ?: "localhost"
            val wsFactory = WebSocketServerHandshakerFactory("ws://$host${URI(req.uri()).path}", null, true)
            val handshaker = wsFactory.newHandshaker(req)
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
                return
            }
            ctx.channel().attr(HANDSHAKER_KEY).set(handshaker)

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


        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                ctx.close()
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
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
            val fullUri = PathAndParams.parse(this.uri())
            val headers = (this.headers() as NettyHttpHeaders).toLightningHeaders()
            val contentTypeHeader = headers.contentType
            val contentLength = headers.contentLength ?: -1L

            val body = if (this.content().isReadable) {
                val bytes = ByteArray(this.content().readableBytes())
                this.content().getBytes(this.content().readerIndex(), bytes)
                TypedData(
                    data = Data.Bytes(bytes),
                    mediaType = contentTypeHeader ?: MediaType.Application.OctetStream,
                )
            } else null

            val hostHeader = this.headers()[HttpHeaderNames.HOST] ?: ""
            val domain = hostHeader.substringBefore(":")
            val sourceIp = cfg.realIpHeader?.let { h ->
                this.headers()[h] ?: run {
                    NettyEngine.logger.warn { "Real IP address header for proxy '$h' was missing from the request." }
                    null
                }
            } ?: ((ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "")

            return HttpRequest(
                path = RawHttpEndpoint(fullUri.pathSegments, HttpMethod(this.method().name())),
                queryParameters = fullUri.queryParameters,
                headers = headers,
                domain = domain.ifEmpty { (ctx.channel().localAddress() as? InetSocketAddress)?.hostString.orEmpty() },
                protocol = "http",
                sourceIp = sourceIp,
                body = body,
            )
        }

        private fun FullHttpRequest.toLightningWebSocketConnectRequest(
            ctx: ChannelHandlerContext,
            cfg: NettyRuntimeSettings,
        ): WebSocketConnectRequest<PathSpec> {
            val fullUri = PathAndParams.parse(this.uri())
            val headers = (this.headers() as NettyHttpHeaders).toLightningHeaders()
            val hostHeader = this.headers()[HttpHeaderNames.HOST] ?: ""
            val domain = hostHeader.substringBefore(":")
            val sourceIp = cfg.realIpHeader?.let { h ->
                this.headers()[h] ?: run {
                    NettyEngine.logger.warn { "Real IP address header for proxy '$h' was missing from the request." }
                    null
                }
            } ?: ((ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "")

            return WebSocketConnectRequest(
                path = RawWebsocketPath(fullUri.pathSegments),
                queryParameters = fullUri.queryParameters,
                headers = headers,
                domain = domain.ifEmpty { (ctx.channel().localAddress() as? InetSocketAddress)?.hostString.orEmpty() },
                protocol = "http",
                sourceIp = sourceIp,
            )
        }

        private fun HttpResponse.toNettyResponse(version: HttpVersion): DefaultFullHttpResponse {
            val status = HttpResponseStatus.valueOf(this.status.code)

            val contentBuf = when (val b = this.body?.data) {
                null -> Unpooled.EMPTY_BUFFER
                is Data.Bytes -> Unpooled.wrappedBuffer(b.data)
                is Data.Text -> Unpooled.wrappedBuffer(b.data.toByteArray(StandardCharsets.UTF_8))
                is Data.Sink -> {
                    val baos = java.io.ByteArrayOutputStream()
                    b.emit(baos.asSink().buffered())
                    Unpooled.wrappedBuffer(baos.toByteArray())
                }

                is Data.Source -> {
                    val baos = java.io.ByteArrayOutputStream()
                    b.source.transferTo(baos.asSink().buffered())
                    Unpooled.wrappedBuffer(baos.toByteArray())
                }
            }

            val res = DefaultFullHttpResponse(version, status, contentBuf)

            for ((key, values) in this.headers.normalizedEntries) {
                for (value in values) {
                    res.headers().add(key, value.toHttpString())
                }
            }
            this.body?.mediaType?.let { mt ->
                res.headers()[HttpHeaderNames.CONTENT_TYPE] = mt.toString()
            }
            if (contentBuf !== Unpooled.EMPTY_BUFFER) {
                res.headers()[HttpHeaderNames.CONTENT_LENGTH] = contentBuf.readableBytes().toString()
            }
            return res
        }

        private fun NettyHttpHeaders.toLightningHeaders(): LsHttpHeaders = LsHttpHeaders(
            buildList {
                for (e in this@toLightningHeaders.entries()) {
                    val key = e.key.toString()
                    val raw = e.value ?: continue
                    raw.split(',').map { it.trim() }.forEach { add(key to it) }
                }
            }
        )
    }

    private abstract class LocalWebSocketConnection<PATH : PathSpec, STORAGE>(
        startingState: STORAGE,
        override val request: WebSocketConnectRequest<PATH>,
        val handler: WebSocketHandler<PATH, STORAGE>,
        val scope: CoroutineScope,
        server: ServerRuntime,
        val pubSub: (request: WebSocketSubscriptionRequest<*, Any?>) -> com.lightningkite.services.pubsub.PubSubChannel<Any?>,
    ) : WebSocketConnection<PATH, STORAGE>, ServerRuntime by server {
        override var currentState: STORAGE = startingState
        override suspend fun repullState(): STORAGE = currentState
        override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
            currentState = modification(currentState)
        }

        override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
            currentState = modification(currentState)
            return currentState
        }

        val subscriptions = HashMap<WebSocketTopic<*, *>, Job>()

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            @Suppress("UNCHECKED_CAST")
            topic as WebSocketSubscriptionRequest<*, Any?>
            subscriptions[topic.topic]?.cancel()
            subscriptions[topic.topic] = scope.launch {
                pubSub(topic).collect { value ->
                    handler.messageFromSubscription(
                        WebSocketSubscriptionMessage(topic.topic, topic.pathInContext.rawPathArguments, value),
                    )
                }
                yield()
            }
        }

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            subscriptions[topic.topic]?.cancel()
        }
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

