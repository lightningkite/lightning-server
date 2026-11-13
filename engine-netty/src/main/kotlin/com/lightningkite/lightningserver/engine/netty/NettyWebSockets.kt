@file:OptIn(InternalLightningServerApi::class, EngineApi::class)

package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.engine.local.LocalWebSocketConnection
import com.lightningkite.lightningserver.engine.local.WsOversizePolicy
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.websockets.*
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.EmptyHttpHeaders
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames.*
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.ScheduledFuture
import kotlinx.coroutines.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import com.lightningkite.lightningserver.websockets.WebSocketFrame as LkWebSocketFrame
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import io.netty.handler.codec.http.HttpRequest as NettyHttpRequest
import kotlinx.coroutines.channels.Channel as CoroutineChannel

private val logger = NettyEngine.logger

/** Whether this, or anything in its chain of causes, is a [T]. */
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.take(16).any { it is T }

// region Upgrading

/**
 * Answers a WebSocket upgrade request: refuses it, or puts a [SocketChannelHandler] in
 * [httpHandler]'s place in the pipeline and starts whatever will consume the socket. That is the
 * handler itself if it is a [DirectExecutableWebSocketHandler], and a [PubSubSession] otherwise.
 *
 * Runs on the channel's event loop.
 */
context(engine: NettyEngine)
internal fun startWebSocket(
    ctx: ChannelHandlerContext,
    httpHandler: ChannelHandler,
    msg: NettyHttpRequest,
    cfg: NettyRuntimeSettings,
) {
    val upgrade = prepareUpgrade(ctx, httpHandler, msg, cfg) ?: return
    val handler = upgrade.handler
    // Direct execution bypasses the pub/sub overhead.
    if (handler is DirectExecutableWebSocketHandler<*, *> && !forceWebSocketPubSub()) {
        @Suppress("UNCHECKED_CAST")
        startDirect(upgrade, handler as DirectExecutableWebSocketHandler<PathSpec, Any?>, cfg)
    } else {
        startPubSub(upgrade, cfg)
    }
}

/** An upgrade request that has passed every check, and is ready to become a socket. */
private class Upgrade(
    val ctx: ChannelHandlerContext,
    private val httpHandler: ChannelHandler,
    val version: HttpVersion,
    val request: WebSocketConnectRequest<PathSpec>,
    val handler: WebSocketHandler<PathSpec, Any?>,
    val handshaker: WebSocketServerHandshaker,
    private val handshakeRequest: FullHttpRequest,
    private val maxMessageSize: Int,
    val settings: WebSocketSettings,
    private val idleTimeout: Duration,
) {
    val channel: Channel get() = ctx.channel()

    /**
     * Swaps this connection's pipeline from HTTP to WebSocket and sends the handshake response.
     *
     * Must run on the channel's event loop: the swap and the handshake then happen with no
     * inbound message able to arrive in between, so the first frame finds [socket] in place.
     * The returned future fails if the handshake does; callers close the connection then.
     */
    fun install(socket: SocketChannelHandler): ChannelFuture {
        val p = channel.pipeline()
        p.replace(httpHandler, WS_HANDLER, socket)
        // Clients may split one message across several frames; handlers are given whole messages.
        p.addBefore(WS_HANDLER, WS_AGGREGATOR, WebSocketFrameAggregator(maxMessageSize))
        // Only the HTTP handler needed its messages one at a time. Removing this passes on what it
        // was still holding, the end marker of the upgrade request, which the socket handler drops.
        p.remove(FLOW_HANDLER)
        p.replace(IDLE_HANDLER, IDLE_HANDLER, livenessCheck(settings, idleTimeout))
        val handshake = try {
            handshaker.handshake(channel, handshakeRequest)
        } catch (e: Exception) {
            // Anything prepareUpgrade's own check let through. The handshaker throws instead of
            // failing its future, so it is answered here and the future fails once that answer is out.
            logger.debug(e) { "Rejecting a WebSocket upgrade the handshaker refused" }
            val refused = channel.newPromise()
            channel.writeAndFlush(plainResponse(version, HttpResponseStatus.BAD_REQUEST))
                .addListener { refused.setFailure(e) }
            return refused
        }
        // A socket reads freely; it pauses itself only while a frame waits for room (see deliver).
        channel.config().isAutoRead = true
        return handshake
    }
}

/**
 * What tells a [SocketChannelHandler] its peer has gone quiet, in either direction, for too long.
 *
 * With pings on, the peer must be heard from within a ping and the time allowed to answer it.
 * That is "answered a ping recently" rather than "sent or received anything recently", so a quiet
 * but healthy socket stays open. With pings off, the socket only has to carry something, either
 * way, within the idle timeout.
 *
 * In both cases a write must also finish within the same time, which is how a peer that has
 * stopped reading is noticed.
 */
private fun livenessCheck(settings: WebSocketSettings, idleTimeout: Duration): IdleStateHandler {
    val ping = settings.ping
    return if (ping != null) {
        val window = (ping + settings.pongTimeout).inWholeMilliseconds
        IdleStateHandler(window, window, 0, TimeUnit.MILLISECONDS)
    } else {
        val window = idleTimeout.inWholeMilliseconds
        IdleStateHandler(0, window, window, TimeUnit.MILLISECONDS)
    }
}

/** Checks an upgrade request over. Answers it and returns null if it cannot become a socket. */
context(engine: NettyEngine)
private fun prepareUpgrade(
    ctx: ChannelHandlerContext,
    httpHandler: ChannelHandler,
    msg: NettyHttpRequest,
    cfg: NettyRuntimeSettings,
): Upgrade? {
    val version = msg.protocolVersion()
    val request = try {
        msg.toLightningWebSocketConnectRequest(ctx, cfg)
    } catch (e: IllegalArgumentException) {
        // Netty's decoder throws this for a malformed request target, such as bad percent-encoding.
        logger.debug(e) { "Rejecting a WebSocket upgrade with a malformed target" }
        ctx.respondAndClose(version, HttpResponseStatus.BAD_REQUEST)
        return null
    } catch (e: Throwable) {
        logger.error(e) { "Could not read a WebSocket upgrade request" }
        ctx.respondAndClose(version, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        return null
    }
    val match = engine.server.endpoints.match(
        engine.externalSerialization.stringArrayFormat,
        request.path.pathSegments
    ) { it.webSocket }
        ?: run {
            val exception = NotFoundException("No webSocket at '${request.path}'")
            logger.error(exception) { "" }
            ctx.respondAndClose(version, HttpResponseStatus.NOT_FOUND)
            return null
        }

    @Suppress("UNCHECKED_CAST")
    val handler = engine.server.interceptIncomingSocket(match.value) as WebSocketHandler<PathSpec, Any?>

    val host = msg.headers()[HOST] ?: "localhost"
    // Netty's own default payload limit is 64 KiB; honour the configured one so both engines
    // bound this peer-driven allocation the same way. The same limit bounds a message
    // reassembled from fragments.
    val maxMessageSize = engine.webSocketMaxMessageSize()
    val handshaker = WebSocketServerHandshakerFactory(
        "ws://$host${QueryStringDecoder(msg.uri()).path()}",
        null,
        true,
        maxMessageSize,
    ).newHandshaker(msg)
    if (handshaker == null) {
        WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
            .addListener(ChannelFutureListener.CLOSE)
        return null
    }
    // The handshaker throws for these, rather than failing its future, and only once the
    // pipeline has been swapped and willConnect has run. Refuse them before either happens.
    if (msg.method() != NettyHttpMethod.GET ||
        (handshaker.version() != WebSocketVersion.V00 && !msg.headers().contains(SEC_WEBSOCKET_KEY))
    ) {
        logger.debug { "Rejecting a WebSocket upgrade with no key or a method other than GET" }
        ctx.respondAndClose(version, HttpResponseStatus.BAD_REQUEST)
        return null
    }
    // The handshake reads only the request line and headers, and an upgrade carries no body,
    // so this is the whole request. Being its own object, it also outlives msg, which is
    // released as soon as the HTTP handler returns — before the pub/sub path gets to use it.
    val handshakeRequest = DefaultFullHttpRequest(
        version, msg.method(), msg.uri(), Unpooled.EMPTY_BUFFER, msg.headers(), EmptyHttpHeaders.INSTANCE
    )
    return Upgrade(
        ctx, httpHandler, version, request, handler, handshaker, handshakeRequest, maxMessageSize,
        webSocketSettings(), cfg.reliability.idleTimeout,
    )
}

/** Upgrades to a socket whose handler runs the whole session itself, reading the socket's messages directly. */
context(engine: NettyEngine)
private fun startDirect(
    upgrade: Upgrade,
    handler: DirectExecutableWebSocketHandler<PathSpec, Any?>,
    cfg: NettyRuntimeSettings,
) {
    val channel = upgrade.channel
    val socket = SocketChannelHandler(
        channel, upgrade.handshaker,
        // 2.10: bounded inbound channel with backpressure instead of Channel.UNLIMITED.
        incoming = engine.inboundChannel(cfg.reliability),
        closeOnOverflow = cfg.reliability.webSocketOversizePolicy == WsOversizePolicy.CLOSE,
        scope = engine.handlerScope,
        ping = upgrade.settings.ping,
    )
    upgrade.install(socket).addListener { handshake ->
        if (!handshake.isSuccess) {
            channel.close()
            return@addListener
        }
        engine.handlerScope.launch {
            try {
                handler.handleDirectAsRoot(
                    request = upgrade.request,
                    incoming = socket.incoming,
                    send = { frame -> socket.sendFrame(frame) },
                    close = { reason -> socket.closeSocket(reason) }
                )
                // The handler runs the whole session, so returning ends it. Close cleanly
                // rather than leave the client connected to nothing; a no-op if the
                // handler or the peer already closed.
                socket.closeSocket(WebSocketClose.NORMAL)
            } catch (e: Throwable) {
                logger.error(e) { "Direct WebSocket handler failed" }
                channel.close()
            } finally {
                // Nothing reads incoming after this; any frame still waiting for room in it is dropped.
                socket.incoming.cancel()
            }
        }
    }
}

/** Runs `willConnect` and, if it accepts, upgrades to a socket driven by a [PubSubSession]. */
context(engine: NettyEngine)
private fun startPubSub(upgrade: Upgrade, cfg: NettyRuntimeSettings) {
    val channel = upgrade.channel
    engine.handlerScope.launch {
        val startingState = try {
            upgrade.handler.willConnectAsRoot(upgrade.request)
        } catch (e: HttpStatusException) {
            logger.debug(e) { "WebSocket willConnect rejected" }
            upgrade.ctx.respondAndClose(upgrade.version, HttpResponseStatus.valueOf(e.status.code))
            return@launch
        } catch (e: Throwable) {
            logger.error(e) { "WebSocket willConnect failed" }
            upgrade.ctx.respondAndClose(upgrade.version, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return@launch
        }

        // A full buffer always holds the peer back here, whatever the overflow policy.
        val socket = SocketChannelHandler(
            channel, upgrade.handshaker,
            incoming = CoroutineChannel(cfg.reliability.webSocketInboundBuffer.coerceAtLeast(1)),
            closeOnOverflow = false,
            scope = engine.handlerScope,
            ping = upgrade.settings.ping,
        )
        // The handler has been told a socket is coming, so from here every way out owes it the
        // matching disconnect.
        val session = PubSubSession(engine, upgrade.handler, upgrade.request, startingState, socket)

        // Back to the event loop for the swap; see Upgrade.install.
        try {
            channel.eventLoop().execute {
                if (!channel.isActive) {
                    // The client left while willConnect ran.
                    session.disconnect(WebSocketClose.GOING_AWAY)
                    return@execute
                }
                upgrade.install(socket).addListener { handshake ->
                    if (handshake.isSuccess) {
                        session.start()
                    } else {
                        channel.close()
                        session.disconnect(WebSocketClose.GOING_AWAY)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            session.disconnect(WebSocketClose.GOING_AWAY)
        }
    }
}

// endregion

/**
 * The last handler of a connection once it has upgraded to a WebSocket. Holds everything about
 * that one socket as plain fields, and owns the protocol housekeeping that is the same whichever
 * way the application consumes the socket: pings, the close handshake, writes that respect
 * backpressure, and feeding messages into the bounded [incoming] channel the application reads.
 *
 * Messages arrive here whole: a [WebSocketFrameAggregator] ahead of this handler joins fragments.
 *
 * Nothing here knows what consumes the socket. That is whoever reads [incoming]: a
 * [DirectExecutableWebSocketHandler], or a [PubSubSession].
 *
 * @param closeOnOverflow what a full [incoming] means. True closes the socket with code 1009
 *   ([WsOversizePolicy.CLOSE]). False makes the frame wait for room, and stops reading the
 *   connection until it has been delivered, which pushes the backpressure onto the peer through
 *   TCP ([WsOversizePolicy.SUSPEND]). With [WsOversizePolicy.DROP_OLDEST] the channel drops by
 *   itself and is never found full.
 */
internal class SocketChannelHandler(
    private val channel: Channel,
    private val handshaker: WebSocketServerHandshaker,
    val incoming: CoroutineChannel<LkWebSocketFrame>,
    private val closeOnOverflow: Boolean,
    /** Where a frame waiting for room in [incoming] waits. */
    private val scope: CoroutineScope,
    /** How often to ping the peer, or null not to. */
    private val ping: Duration?,
) : SimpleChannelInboundHandler<WebSocketFrame>() {

    /**
     * Why the peer's side of the socket ended, or null while it is still open: [WebSocketClose.NORMAL]
     * once it has sent a close frame, and [WebSocketClose.GOING_AWAY] if the connection simply went,
     * which is the client vanishing or the server going down rather than an error.
     */
    @Volatile
    var endReason: WebSocketClose? = null
        private set

    /** A close frame goes out at most once, whichever side starts the close. */
    private val closeSent = AtomicBoolean(false)
    private var pinger: ScheduledFuture<*>? = null

    /** The last ping sent. The next is held back until it has left, so pings cannot pile up behind a stalled peer. */
    private var lastPing: ChannelFuture? = null

    /**
     * How much of the message at the front of the outbound buffer had been sent when writing was
     * last found idle, or -1 if nothing was waiting to be sent then.
     */
    private var stalledAt = -1L

    /** Frames waiting for room in [incoming]. Only touched on the event loop. */
    private var waiting = 0

    /** The peer has closed, but frames it sent first are still [waiting] to be delivered. */
    private var closeWhenDelivered = false

    /**
     * The peer will send nothing more, for [reason]: ends [incoming] once what it already sent has
     * been delivered.
     */
    private fun endIncoming(reason: WebSocketClose) {
        if (endReason == null) endReason = reason
        if (waiting == 0) incoming.close() else closeWhenDelivered = true
    }

    suspend fun sendFrame(frame: LkWebSocketFrame) {
        channel.writeAwaitingRoom(
            when (frame) {
                is LkWebSocketFrame.Binary -> BinaryWebSocketFrame(Unpooled.wrappedBuffer(frame.content))
                is LkWebSocketFrame.Text -> TextWebSocketFrame(frame.content)
            },
            throwOnFailure = false,
        )
    }

    fun closeSocket(reason: WebSocketClose) {
        if (closeSent.compareAndSet(false, true)) {
            channel.writeAndFlush(CloseWebSocketFrame(reason.code.code.toInt(), reason.truncatedMessage)).addListener(ChannelFutureListener.CLOSE)
        }
    }

    /** 2.10: hands a message to the application, applying the overflow policy when [incoming] is full. */
    private fun deliver(ctx: ChannelHandlerContext, frame: LkWebSocketFrame) {
        // While anything is waiting, later frames must queue behind it to keep their order.
        if (waiting == 0) {
            val result = incoming.trySend(frame)
            if (result.isSuccess || result.isClosed) return
            if (closeOnOverflow) {
                closeSocket(WebSocketClose(WebSocketClose.Code.TOO_BIG, "WebSocket inbound buffer overflow"))
                return
            }
        }
        // Frames already decoded when reading stops still arrive, and queue here in order.
        if (waiting++ == 0) channel.config().isAutoRead = false
        scope.launch(ctx.executor().asCoroutineDispatcher()) {
            try {
                incoming.send(frame)
            } catch (_: Exception) {
                // The application's channel closed; the socket is ending.
            } finally {
                if (--waiting == 0) {
                    channel.config().isAutoRead = true
                    if (closeWhenDelivered) incoming.close()
                }
            }
        }
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        val period = ping?.inWholeMilliseconds?.coerceAtLeast(1) ?: return
        pinger = ctx.executor().scheduleAtFixedRate(
            { if (channel.isActive && lastPing?.isDone != false) lastPing = channel.writeAndFlush(PingWebSocketFrame()) },
            period, period, TimeUnit.MILLISECONDS
        )
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        pinger?.cancel(false)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // The empty end-of-request marker that trails the upgrade request; it means nothing here.
        if (msg is HttpObject) ReferenceCountUtil.release(msg)
        else super.channelRead(ctx, msg)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        when (frame) {
            is TextWebSocketFrame -> deliver(ctx, LkWebSocketFrame(frame.text()))
            is BinaryWebSocketFrame -> deliver(ctx, LkWebSocketFrame(ByteBufUtil.getBytes(frame.content())))
            is CloseWebSocketFrame -> {
                endIncoming(WebSocketClose.NORMAL)
                // Echo the close so the client sees a clean close with its own code, not a
                // dropped connection. If we closed first, this frame *is* the echo.
                if (closeSent.compareAndSet(false, true)) handshaker.close(ctx, frame.retain())
                else ctx.close()
            }

            is PingWebSocketFrame -> ctx.writeAndFlush(PongWebSocketFrame(frame.content().retain()))
            else -> { /* pongs; their arrival alone is what keeps the idle check satisfied */
            }
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when {
            evt !is IdleStateEvent -> super.userEventTriggered(ctx, evt)

            evt.state() == IdleState.WRITER_IDLE -> if (peerStoppedReading(evt)) {
                logger.debug { "Closing a WebSocket whose peer has stopped reading" }
                ctx.close()
            }

            // While frames wait for room the connection is deliberately not read, so the peer's
            // pongs go unseen. That silence is the application's doing, not the peer's; the check
            // fires again once reading resumes, and a peer that is really gone is caught then.
            // A peer that is not reading either is caught by the check above in the meantime.
            waiting > 0 -> {}

            else -> {
                logger.debug { "Closing an unresponsive WebSocket" }
                ctx.close()
            }
        }
    }

    /**
     * Whether what is queued for the peer has gone nowhere for a whole liveness window.
     *
     * Called each time a window passes with no write finishing. That alone is not enough: nothing
     * may be queued, or one large message may be going out slowly. So the first such window since
     * a write last finished only notes how far the message at the front has got, and a later one
     * that finds it no further along is a peer that has stopped reading.
     */
    private fun peerStoppedReading(evt: IdleStateEvent): Boolean {
        val outbound = channel.unsafe().outboundBuffer()
        val progress = if (outbound == null || outbound.isEmpty) -1L else outbound.currentProgress()
        val stalled = !evt.isFirst && progress >= 0 && progress == stalledAt
        stalledAt = progress
        return stalled
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        pinger?.cancel(false)
        // Whoever consumes incoming cancels it when they stop, which ends any wait for room.
        endIncoming(WebSocketClose.GOING_AWAY)
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when {
            // A compressed frame that is not valid deflate data.
            cause.hasCause<java.util.zip.DataFormatException>() -> {
                logger.debug(cause) { "Closing a WebSocket that sent a frame that would not decompress" }
                closeSocket(WebSocketClose(WebSocketClose.Code.NOT_CONSISTENT, "Invalid compressed message"))
            }

            // A message over the size limit, as sent or once a compressed frame is inflated.
            cause is TooLongFrameException ||
                    cause.hasCause<io.netty.handler.codec.compression.DecompressionException>() -> {
                logger.debug(cause) { "Closing a WebSocket that sent a message over the size limit" }
                closeSocket(WebSocketClose(WebSocketClose.Code.TOO_BIG, "Message too large"))
            }

            else -> {
                logPipelineFailure("WebSocket", cause)
                ctx.close()
            }
        }
    }
}

/**
 * A socket driven through the standard lifecycle: `willConnect` has already run, and this runs
 * `didConnect`, then `messageFromClient` for each message in turn, then `disconnect` exactly once.
 *
 * Messages are handled one at a time, in arrival order, by a single coroutine: the next is not
 * started until the handler has finished with the one before. When the handler falls behind, the
 * connection stops being read rather than queueing without limit.
 */
internal class PubSubSession(
    private val engine: NettyEngine,
    private val handler: WebSocketHandler<PathSpec, Any?>,
    request: WebSocketConnectRequest<PathSpec>,
    startingState: Any?,
    private val socket: SocketChannelHandler,
) {
    /**
     * Holds this socket's subscription collectors. A child of the engine's scope, so shutdown also
     * stops it. Cancelled once the socket disconnects.
     */
    private val subscriptions = engine.handlerScope.coroutineContext.let {
        CoroutineScope(it + SupervisorJob(it.job) + Dispatchers.IO)
    }

    private val disconnected = AtomicBoolean(false)

    private val connection: WebSocketConnection<PathSpec, Any?> = object : LocalWebSocketConnection<PathSpec, Any?>(
        startingState = startingState,
        request = request,
        handler = handler,
        scope = subscriptions,
        pubSub = { engine.subscriptionChannel(it) }
    ) {
        context(server: ServerRuntime)
        override suspend fun send(frame: LkWebSocketFrame) = socket.sendFrame(frame)

        context(server: ServerRuntime)
        override suspend fun close(reason: WebSocketClose) = socket.closeSocket(reason)
    }

    init {
        // So shutdown can find it; see disconnect.
        engine.pubSubSessions.add(this)
    }

    /**
     * Runs the socket's lifecycle once the handshake has succeeded.
     *
     * The disconnect is raised from here, after the last message has been handled, so a handler
     * never sees a message for a socket it has already been told is gone.
     */
    fun start() {
        engine.handlerScope.launch {
            // Shutdown may already have disconnected this socket, while its handshake was on the way.
            if (disconnected.get()) return@launch
            val reason = try {
                with(engine) {
                    try {
                        handler.didConnectAsRoot(connection)
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        logger.warn(e) { "WebSocket didConnect failed; disconnecting" }
                        throw e
                    }
                    for (frame in socket.incoming) handler.messageFromClientAsRoot(connection, frame)
                }
                socket.endReason ?: WebSocketClose.NORMAL
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // Reported through the normal disconnect lifecycle rather than a bare close, so
                // the handler's own cleanup still runs.
                WebSocketClose.exceptional(e)
            }
            disconnect(reason)
        }
    }

    /**
     * Raises the socket's disconnect phase, at most once.
     *
     * Every route that ends a socket funnels through here — the message loop finishing, a failing
     * handler, a connection that never completed its handshake, the server shutting down — because
     * several can fire for the same socket and only the first of them is the socket's actual
     * disconnect. Shutdown calls this itself rather than waiting for the message loop to, because
     * it cancels the scope that loop runs in.
     *
     * For the same reason this runs on the engine's cleanup scope: it is normally called *because*
     * something is tearing the socket down, and a disconnect launched on a scope that teardown
     * cancels would never run.
     */
    fun disconnect(reason: WebSocketClose) {
        if (!disconnected.compareAndSet(false, true)) return
        engine.pubSubSessions.remove(this)
        engine.disconnectScope.launch {
            try {
                // Guarantees the connection gets closed even if the handler's own disconnect() throws.
                with(engine) { handler.disconnectAndCloseAsRoot(connection, reason) }
            } catch (e: Exception) {
                logger.error(e) { "disconnect handling failed" }
            } finally {
                subscriptions.cancel()
                // Nothing reads incoming once the socket is disconnected; any frame still waiting
                // for room in it is dropped rather than left suspended forever.
                socket.incoming.cancel()
            }
        }
    }
}
