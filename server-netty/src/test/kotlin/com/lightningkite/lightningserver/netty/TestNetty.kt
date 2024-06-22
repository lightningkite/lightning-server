package com.lightningkite.lightningserver.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.CharsetUtil
import java.util.*


object RequestUtils {
    fun formatParams(request: HttpRequest): StringBuilder {
        val responseData = StringBuilder()
        val queryStringDecoder = QueryStringDecoder(request.uri())
        val params = queryStringDecoder.parameters()
        if (params.isNotEmpty()) {
            for ((key, vals) in params) {
                for (it in vals) {
                    responseData.append("Parameter: ").append(key.uppercase(Locale.getDefault())).append(" = ")
                        .append(it.uppercase(Locale.getDefault())).append("\r\n")
                }
            }
            responseData.append("\r\n")
        }
        return responseData
    }

    fun formatBody(httpContent: HttpContent): java.lang.StringBuilder {
        val responseData = StringBuilder()
        val content: ByteBuf = httpContent.content()
        if (content.isReadable) {
            responseData.append(content.toString(CharsetUtil.UTF_8).uppercase(Locale.getDefault()))
                .append("\r\n")
        }
        return responseData
    }

    fun prepareLastResponse(request: HttpRequest?, trailer: LastHttpContent): StringBuilder {
        val responseData = StringBuilder()
        responseData.append("Good Bye!\r\n")

        if (!trailer.trailingHeaders().isEmpty) {
            responseData.append("\r\n")
            for (name in trailer.trailingHeaders().names()) {
                for (value in trailer.trailingHeaders().getAll(name)) {
                    responseData.append("P.S. Trailing Header: ")
                    responseData.append(name).append(" = ").append(value).append("\r\n")
                }
            }
            responseData.append("\r\n")
        }
        return responseData
    }

    fun evaluateDecoderResult(o: HttpObject?): StringBuilder {
        val responseData = StringBuilder()
        val result = o?.decoderResult()

        if (result?.isSuccess == false) {
            responseData.append("..Decoder Failure: ")
            responseData.append(result.cause())
            responseData.append("\r\n")
        }

        return responseData
    }
}


/**
 * Handles a server-side channel.
 */
class EchoServerHandler : SimpleChannelInboundHandler<Any>() {
    private var request: HttpRequest? = null
    var responseData: StringBuilder = StringBuilder()

    private fun writeResponse(ctx: ChannelHandlerContext) {
        val response: FullHttpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE,
            Unpooled.EMPTY_BUFFER
        )
        ctx.write(response)
    }

    private fun writeResponse(
        ctx: ChannelHandlerContext,
        trailer: LastHttpContent,
        responseData: StringBuilder,
    ) {
        val keepAlive = HttpUtil.isKeepAlive(request)
        val httpResponse: FullHttpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            if ((trailer as HttpObject).decoderResult().isSuccess) HttpResponseStatus.OK else HttpResponseStatus.BAD_REQUEST,
            Unpooled.copiedBuffer(responseData.toString(), CharsetUtil.UTF_8)
        )

        httpResponse.headers()[HttpHeaderNames.CONTENT_TYPE] = "text/plain; charset=UTF-8"

        if (keepAlive) {
            httpResponse.headers().setInt(
                HttpHeaderNames.CONTENT_LENGTH,
                httpResponse.content().readableBytes()
            )
            httpResponse.headers()[HttpHeaderNames.CONNECTION] = HttpHeaderValues.KEEP_ALIVE
        }
        ctx.write(httpResponse)

        if (!keepAlive) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
        }
    }

    // (1)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) { // (2)
        println("In Channel Read")
        (msg as? HttpRequest)?.also {
            request = it

            if (HttpUtil.is100ContinueExpected(it)) {
                writeResponse(ctx)
            }
            responseData.setLength(0)
            responseData.append(RequestUtils.formatParams(it))
        }
        responseData.append(RequestUtils.evaluateDecoderResult(request))

        (msg as? HttpContent)?.also { httpContent ->
            responseData.append(RequestUtils.formatBody(httpContent))
            responseData.append(RequestUtils.evaluateDecoderResult(request))

            (httpContent as? LastHttpContent)?.also { lastResponse ->
                responseData.append(RequestUtils.prepareLastResponse(request, lastResponse))
                writeResponse(ctx, lastResponse, responseData)
            }
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }


    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { // (4)
        // Close the connection when an exception is raised.
        cause.printStackTrace()
        ctx.close()
    }
}

/**
 * Discards any incoming data.
 */
class EchoServer(private val port: Int) {
    @Throws(Exception::class)
    fun run() {
        val bossGroup: EventLoopGroup = NioEventLoopGroup() // (1)
        val workerGroup: EventLoopGroup = NioEventLoopGroup()
        try {
            val b = ServerBootstrap() // (2)
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // (3)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    // (4)
                    @Throws(Exception::class)
                    override fun initChannel(ch: SocketChannel) {
                        println("In Init Channel")
                        ch.pipeline()
                            .addLast(
                                HttpRequestDecoder(),
                                HttpResponseEncoder(),
                                EchoServerHandler(),
                            )
                    }
                })
                .option<Int>(ChannelOption.SO_BACKLOG, 128) // (5)
                .childOption<Boolean>(ChannelOption.SO_KEEPALIVE, true) // (6)


            // Bind and start to accept incoming connections.
            val f = b.bind(port).sync() // (7)


            // Wait until the server socket is closed.
            // In this example, this does not happen, but you can do that to gracefully
            // shut down your server.
            f.channel().closeFuture().sync()
        } finally {
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
        }
    }

    companion object {
        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            var port = 8080
            if (args.size > 0) {
                port = args[0].toInt()
            }

            EchoServer(port).run()
        }
    }
}