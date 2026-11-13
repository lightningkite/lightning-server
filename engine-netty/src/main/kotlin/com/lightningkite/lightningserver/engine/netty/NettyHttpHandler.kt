@file:OptIn(InternalLightningServerApi::class, EngineApi::class)

package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.engine.local.enforceBodyLimit
import com.lightningkite.lightningserver.engine.local.hasRequestBody
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.*
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpHeaderNames.*
import io.netty.handler.flow.FlowControlHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.util.concurrent.RejectedExecutionException
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders
import io.netty.handler.codec.http.HttpMethod as NettyHttpMethod
import io.netty.handler.codec.http.HttpRequest as NettyHttpRequest

private val logger = NettyEngine.logger

/**
 * However small the body limit, this much of a request body nobody is reading is still taken in and
 * thrown away before the connection is cut. Many clients send their whole upload before reading any
 * of the response, and cutting them off sooner can cost them the response itself.
 */
private const val MIN_DISCARD_BYTES = 1024L * 1024

/**
 * A whole response the engine answers with itself, with no handler involved: [text] as a plain-text
 * body, or no body at all. Tells the client the connection is closing unless [keepAlive].
 */
internal fun plainResponse(
    version: HttpVersion,
    status: HttpResponseStatus,
    text: String = "",
    keepAlive: Boolean = false,
): FullHttpResponse {
    val body = text.toByteArray()
    val res = DefaultFullHttpResponse(version, status, Unpooled.wrappedBuffer(body))
    if (body.isNotEmpty()) res.headers()[CONTENT_TYPE] = "text/plain; charset=utf-8"
    res.headers()[CONTENT_LENGTH] = body.size.toString()
    res.headers()[CONNECTION] = if (keepAlive) HttpHeaderValues.KEEP_ALIVE else HttpHeaderValues.CLOSE
    return res
}

/** Answers with a bodyless [status], and closes the connection once that is out. */
internal fun ChannelHandlerContext.respondAndClose(version: HttpVersion, status: HttpResponseStatus) {
    writeAndFlush(plainResponse(version, status)).addListener(ChannelFutureListener.CLOSE)
}

/** Logs a pipeline failure. A peer dropping its connection is routine; anything else is worth seeing. */
internal fun logPipelineFailure(what: String, cause: Throwable) {
    if (cause is IOException) logger.debug(cause) { "$what: connection failed" }
    else logger.warn(cause) { "$what: unexpected pipeline failure; closing the connection" }
}

/**
 * Answers `Expect: 100-continue`, refusing an upload whose declared length is already over the
 * limit so the client never sends a body that is going to be rejected.
 *
 * Netty's own rejection swallows the request but leaves the connection open, so a client that
 * sends its body anyway (curl does, after a short wait) would have all of it read and thrown away
 * with no limit. This one closes the connection once the 413 is out, drops whatever arrives in the
 * meantime, and stops reading the socket.
 */
internal class BodyLimitExpectContinueHandler(private val maxBody: Long) : HttpServerExpectContinueHandler() {
    private var rejected = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when {
            rejected -> ReferenceCountUtil.release(msg)
            // Nothing in a request the decoder rejected can be relied on, its length included. The
            // HTTP handler answers it with a 400.
            msg is NettyHttpRequest && msg.decoderResult().isFailure -> ctx.fireChannelRead(msg)
            msg is NettyHttpRequest &&
                    HttpUtil.is100ContinueExpected(msg) &&
                    HttpUtil.getContentLength(msg, -1L) > maxBody -> {
                rejected = true
                ctx.channel().config().isAutoRead = false
                ctx.respondAndClose(msg.protocolVersion(), HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)
                ReferenceCountUtil.release(msg)
            }

            else -> super.channelRead(ctx, msg)
        }
    }
}

// region Request conversion

/** The parts of an incoming request that HTTP requests and WebSocket upgrades read the same way. */
private class RequestParts(
    val path: String,
    val queryParameters: QueryParameters,
    val headers: HttpHeaders,
    val domain: String,
    val sourceIp: String,
    val identity: RequestIdentity,
)

context(engine: Engine)
private fun NettyHttpRequest.parts(ctx: ChannelHandlerContext, cfg: NettyRuntimeSettings): RequestParts {
    val uri = QueryStringDecoder(this.uri())
    val headers = this.headers().toLightningHeaders()
    val identity = headers.requestIdentity(cfg.requestIdHeader) {
        logger.warn { "Request ID header for proxy '${cfg.requestIdHeader}' was missing from the request." }
    }
    val sourceIp = cfg.realIpHeader?.let { header ->
        this.headers()[header].also { realIp ->
            if (realIp == null)
                logger.warn { "Real IP address header for proxy '$header' was missing from request [${identity.requestId}]." }
        }
    } ?: ((ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "")
    val domain = (this.headers()[HOST] ?: "").substringBefore(":")
    return RequestParts(
        path = uri.path(),
        queryParameters = QueryParameters(uri.parameters().flatMap { (key, values) -> values.map { key to it } }),
        headers = headers,
        domain = domain.ifEmpty { (ctx.channel().localAddress() as? InetSocketAddress)?.hostString.orEmpty() },
        sourceIp = sourceIp,
        identity = identity,
    )
}

context(engine: Engine)
private fun NettyHttpRequest.toLightningHttpRequest(
    ctx: ChannelHandlerContext,
    cfg: NettyRuntimeSettings,
    body: SuspendingSource?,
    declaredLength: Long?,
): Pair<HttpRequest<PathSpec>, Execution.ID> {
    val parts = parts(ctx, cfg)
    val adapted = HttpRequest(
        path = RawHttpEndpoint(parts.path, HttpMethod(this.method().name())),
        queryParameters = parts.queryParameters,
        headers = parts.headers,
        domain = parts.domain,
        protocol = "http",
        sourceIp = parts.sourceIp,
        upstreamRequestId = parts.identity.upstreamRequestId,
        body = body?.let {
            TypedData.suspendingSource(
                source = it,
                mediaType = parts.headers.contentType ?: MediaType.Application.OctetStream,
                size = declaredLength,
            )
        },
    )
    return adapted to parts.identity.requestId
}

context(engine: Engine)
internal fun NettyHttpRequest.toLightningWebSocketConnectRequest(
    ctx: ChannelHandlerContext,
    cfg: NettyRuntimeSettings,
): WebSocketConnectRequest<PathSpec> {
    val parts = parts(ctx, cfg)
    return WebSocketConnectRequest(
        path = RawWebSocketPath(parts.path),
        socketId = parts.identity.requestId,
        queryParameters = parts.queryParameters,
        headers = parts.headers,
        domain = parts.domain,
        protocol = "http",
        sourceIp = parts.sourceIp,
        upstreamRequestId = parts.identity.upstreamRequestId,
    )
}

private fun NettyHttpHeaders.toLightningHeaders(): HttpHeaders =
    HttpHeaders(entries().map { Pair(it.key, it.value) })

// endregion

/**
 * The last handler of a connection while it speaks HTTP. Answers ordinary requests, and hands a
 * WebSocket upgrade to [startWebSocket], which puts a [SocketChannelHandler] in this handler's place.
 *
 * Requests arrive in pieces — the request line and headers, then body chunks — and are answered
 * one at a time. Handlers run on the engine's scope, never on this connection's event loop;
 * everything in this class other than the coroutine in [handleHttp] runs on the event loop and needs
 * no locking.
 *
 * No message arrives here until there is somewhere for it to go. The connection is read only while
 * that is so (see [updateReading]), and the [FlowControlHandler] ahead of this handler holds back
 * whatever else the decoder had already produced when reading stopped. So nothing of the next
 * request turns up while this one waits for its response, and no more of a body whose buffer is full.
 */
internal class HttpChannelHandler(
    private val engine: NettyEngine,
    private val cfg: NettyRuntimeSettings,
    private val maxBody: Long,
) : SimpleChannelInboundHandler<HttpObject>() {

    /** One request and its response. */
    private class Exchange(val body: RequestBodySource?) {
        /** The whole request, body included, has been received. */
        @Volatile
        var requestDone = false

        /**
         * The handler is running and has not returned its response yet. Its own timeout bounds
         * this; sending the response afterwards is bounded by the idle check instead.
         */
        @Volatile
        var handling = false

        /**
         * The last write of the response, once it has been sent. From then on, anything still to
         * come of the request is read only to be thrown away.
         */
        var response: ChannelFuture? = null
        var discarded = 0L
    }

    private var current: Exchange? = null

    /**
     * This handler takes nothing more from the connection: a request has been refused and the
     * connection is closing, or it is becoming a WebSocket.
     */
    private var stopped = false

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        when (msg) {
            is NettyHttpRequest -> begin(ctx, msg)
            is HttpContent -> content(ctx, msg)
        }
        updateReading(ctx)
    }

    /**
     * Reads the connection only while there is somewhere for the next message to go. Called after
     * anything that could change that.
     */
    private fun updateReading(ctx: ChannelHandlerContext) {
        if (stopped) return
        val exchange = current
        ctx.channel().config().isAutoRead = when {
            exchange == null -> true
            exchange.requestDone -> false
            exchange.response != null -> true
            else -> exchange.body?.wantsMore() ?: true
        }
    }

    private fun stop(ctx: ChannelHandlerContext) {
        stopped = true
        ctx.channel().config().isAutoRead = false
    }

    /** Answers with [status] and closes, taking nothing else the client sends meanwhile. */
    private fun refuse(ctx: ChannelHandlerContext, version: HttpVersion, status: HttpResponseStatus) {
        stop(ctx)
        ctx.respondAndClose(version, status)
    }

    /**
     * Shutdown has begun: closes the connection if it is between requests. A connection with a
     * request in progress closes itself once that response is out (see [responseDone]).
     */
    fun closeIfIdle(channel: Channel) {
        if (current == null && !stopped) channel.close()
    }

    private fun begin(ctx: ChannelHandlerContext, msg: NettyHttpRequest) {
        val version = msg.protocolVersion()
        if (msg.decoderResult().isFailure) {
            logger.debug(msg.decoderResult().cause()) { "Rejecting a malformed HTTP request" }
            refuse(ctx, version, HttpResponseStatus.BAD_REQUEST)
            return
        }
        val connection = msg.headers()[CONNECTION]?.lowercase() ?: ""
        val upgrade = msg.headers()[UPGRADE]?.lowercase() ?: ""
        if (connection.contains("upgrade") && upgrade == "websocket") {
            stop(ctx)
            with(engine) { startWebSocket(ctx, this@HttpChannelHandler, msg, cfg) }
            return
        }

        val declaredLength = HttpUtil.getContentLength(msg, -1L).takeIf { it >= 0 }
        if (declaredLength != null && declaredLength > maxBody) {
            // 2.5: reject oversized bodies by declared Content-Length before reading the body.
            val exchange = Exchange(null).also { current = it }
            val refusal = plainResponse(version, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "Payload Too Large")
            responseDone(ctx, exchange, ctx.writeAndFlush(refusal), reusable = false)
            return
        }

        val hasBody = hasRequestBody(declaredLength, msg.headers().getAll(TRANSFER_ENCODING))
        val body = if (hasBody) RequestBodySource(ctx.channel().eventLoop(), maxBody) { updateReading(ctx) } else null
        val adapted = try {
            with(engine) { msg.toLightningHttpRequest(ctx, cfg, body, declaredLength) }
        } catch (e: IllegalArgumentException) {
            // Netty's decoder throws this for a malformed request target, such as bad percent-encoding.
            logger.debug(e) { "Rejecting a request with a malformed target" }
            refuse(ctx, version, HttpResponseStatus.BAD_REQUEST)
            return
        } catch (e: Exception) {
            logger.error(e) { "Could not read an HTTP request" }
            refuse(ctx, version, HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return
        }
        val exchange = Exchange(body).also { current = it }
        // A bodyless request counts as complete now, not when the empty end marker the decoder
        // sends after it turns up. The handler may answer first on another thread, and this way it
        // always finds the request complete and keeps the connection alive. The marker is read once
        // the response is out, and ignored (see content).
        if (body == null) exchange.requestDone = true
        handleHttp(
            ctx = ctx,
            exchange = exchange,
            version = version,
            keepAlive = HttpUtil.isKeepAlive(msg),
            isHead = msg.method() == NettyHttpMethod.HEAD,
            adapted = adapted,
        )
    }

    private fun content(ctx: ChannelHandlerContext, msg: HttpContent) {
        // With no request in progress, this is the end marker of a bodyless request already answered.
        val exchange = current ?: return
        if (msg.decoderResult().isFailure) {
            exchange.body?.fail(IOException("Malformed request body", msg.decoderResult().cause()))
            ctx.close()
            return
        }
        val bytes = msg.content()
        if (bytes.isReadable) {
            val kept = exchange.response == null && exchange.body?.offer(bytes) == true
            if (!kept) {
                // Nobody will read these: the response is out, or the handler closed the body
                // while it carries on. The socket is still read so a client that will not look
                // at the response until its upload is done is not deadlocked, but only so far.
                exchange.discarded += bytes.readableBytes()
                if (exchange.discarded > maxOf(maxBody, MIN_DISCARD_BYTES)) {
                    // Not worth reading any further just to be polite about the close.
                    ctx.close()
                    return
                }
            }
        }
        if (msg is LastHttpContent) {
            exchange.requestDone = true
            exchange.body?.complete()
            // The response went out while the body was still arriving, so the connection was
            // kept open only to take the rest of it in.
            exchange.response?.addListener(ChannelFutureListener.CLOSE)
        }
    }

    private fun handleHttp(
        ctx: ChannelHandlerContext,
        exchange: Exchange,
        version: HttpVersion,
        keepAlive: Boolean,
        isHead: Boolean,
        adapted: Pair<HttpRequest<PathSpec>, Execution.ID>,
    ) {
        val (request, executionId) = adapted
        val channel = ctx.channel()

        // A connection still delivering the body of this request cannot carry the next one, and
        // neither can one that is being shut down.
        fun reusable() = keepAlive && exchange.requestDone && !engine.draining

        // Started lazily so it is in inFlight before it can possibly finish.
        val job = engine.handlerScope.launch(start = CoroutineStart.LAZY) {
            var committed = false
            val sent: Sent = try {
                // Request timeout is enforced centrally in ServerRuntime.handle (per-handler HttpHandler.timeout).
                val handled: HttpResponse = try {
                    engine.handleRoot(request, executionId)
                } finally {
                    exchange.handling = false
                }
                // 2.5: a body that passed the cap mid-read is a 413, whatever the handler made of it.
                val result = with(engine) { enforceBodyLimit(handled, exchange.body?.exceeded == true) }
                sendResponse(
                    channel = channel,
                    response = result,
                    version = version,
                    isHead = isHead,
                    reusable = reusable(),
                    streaming = !engine.disableResponseStreaming(request),
                    onCommitted = { committed = true },
                )
            } catch (e: Throwable) {
                if (e is CancellationException && !isActive) {
                    // The engine is shutting down and cancelled this handler: not an error, and
                    // nothing is coming that could be sent.
                    channel.close()
                    throw e
                }
                if (committed) {
                    // Part of the response is already out. Closing is the only way to keep the
                    // client from taking a short body for a complete one. A client that went away
                    // mid-download is routine; a body that failed with its client still there is not.
                    if (channel.isActive) logger.warn(e) { "A response body failed part-way; closing the connection" }
                    else logger.debug(e) { "A response body was cut short: the connection closed" }
                    channel.close()
                    return@launch
                }
                logger.error(e) { "Netty error!" }
                val reusable = reusable()
                val res = plainResponse(
                    version, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", reusable
                )
                Sent(channel.writeAndFlush(res), reusable)
            } finally {
                request.body?.close()
            }
            try {
                channel.eventLoop().execute { responseDone(ctx, exchange, sent.lastWrite, sent.reusable) }
            } catch (_: RejectedExecutionException) {
                // The loop is shutting down and takes the connection with it.
            }
        }
        exchange.handling = true
        engine.inFlight.add(job)
        job.invokeOnCompletion { engine.inFlight.remove(job) }
        job.start()
    }

    /**
     * The response to [exchange] has been sent, ending with [lastWrite]: move on to the next
     * request, or wind the connection down.
     */
    private fun responseDone(ctx: ChannelHandlerContext, exchange: Exchange, lastWrite: ChannelFuture, reusable: Boolean) {
        if (current !== exchange) return
        exchange.response = lastWrite
        when {
            // The client is still sending a body nobody will read. Closing now would reset the
            // connection under it and could cost it the response, so the rest is taken in (up to
            // a limit) and the connection closes once it has all arrived (see content).
            !exchange.requestDone -> {}

            // Draining is checked here too: a response that started before shutdown may have
            // promised keep-alive, but the connection still closes once it is out.
            reusable && !engine.draining -> current = null

            // Closing sooner than the last write would drop whatever is unsent.
            else -> lastWrite.addListener(ChannelFutureListener.CLOSE)
        }
        updateReading(ctx)
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            if (!serverIsBusy()) ctx.close()
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }

    /**
     * A handler is running and is not waiting on the client, so the connection is quiet because
     * of the server, not the client. Nothing is read while a handler works on a request it has
     * whole, or while its body's buffer is full, so a handler slower than the idle timeout would
     * otherwise lose its connection. The handler's own timeout bounds this instead.
     */
    private fun serverIsBusy(): Boolean {
        val exchange = current ?: return false
        if (!exchange.handling) return false
        return exchange.requestDone || exchange.body?.wantsMore() == false
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        current?.body?.fail(ClosedChannelException())
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logPipelineFailure("HTTP", cause)
        current?.body?.fail(cause)
        ctx.close()
    }
}
