package com.lightningkite.lightningserver.netty2echo

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http2.DefaultHttp2DataFrame
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.util.CharsetUtil

/**
 * Handles a server-side channel.
 */
class EchoServerHandler : ChannelDuplexHandler() {
//    private var request: HttpRequest? = null
//    var responseData: StringBuilder = StringBuilder()

//    private fun writeResponse(ctx: ChannelHandlerContext) {
//        val response: FullHttpResponse = DefaultFullHttpResponse(
//            HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE,
//            Unpooled.EMPTY_BUFFER
//        )
//        ctx.write(response)
//    }
//
//    private fun writeResponse(
//        ctx: ChannelHandlerContext,
//        trailer: LastHttpContent,
//        responseData: StringBuilder,
//    ) {
//        val keepAlive = HttpUtil.isKeepAlive(request)
//        val httpResponse: FullHttpResponse = DefaultFullHttpResponse(
//            HttpVersion.HTTP_1_1,
//            if ((trailer as HttpObject).decoderResult().isSuccess) HttpResponseStatus.OK else HttpResponseStatus.BAD_REQUEST,
//            Unpooled.copiedBuffer(responseData.toString(), CharsetUtil.UTF_8)
//        )
//
//        httpResponse.headers()[HttpHeaderNames.CONTENT_TYPE] = "text/plain; charset=UTF-8"
//
//        if (keepAlive) {
//            httpResponse.headers().setInt(
//                HttpHeaderNames.CONTENT_LENGTH,
//                httpResponse.content().readableBytes()
//            )
//            httpResponse.headers()[HttpHeaderNames.CONNECTION] = HttpHeaderValues.KEEP_ALIVE
//        }
//        ctx.write(httpResponse)
//
//        if (!keepAlive) {
//            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
//        }
//    }

    companion object {
        val RESPONSE_BYTES: ByteBuf =
            Unpooled.unreleasableBuffer(Unpooled.copiedBuffer("Hello World", CharsetUtil.UTF_8))
    }

    @Throws(java.lang.Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Http2HeadersFrame) {
            if (msg.isEndStream) {
                val content = ctx.alloc()
                    .buffer()
                content.writeBytes(RESPONSE_BYTES.duplicate())

                val headers = DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText())
                ctx.write(DefaultHttp2HeadersFrame(headers).stream(msg.stream()))
                ctx.write(DefaultHttp2DataFrame(content, true).stream(msg.stream()))
            }
        } else {
            super.channelRead(ctx, msg)
        }
    }

//    // (1)
//    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { // (2)
//        println("In Channel Read")
//        (msg as? Http2HeadersFrame)?.also {
//            if (msg.isEndStream) {
//                val content = ctx.alloc().buffer()
//                content.writeBytes(RESPONSE_BYTES.duplicate())
//
//                val headers = DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText())
//                ctx.write(DefaultHttp2HeadersFrame(headers).stream(msg.stream()))
//                ctx.write(DefaultHttp2DataFrame(content, true).stream(msg.stream()))
//            }
//            request = it
//
//            if (HttpUtil.is100ContinueExpected(it)) {
//                writeResponse(ctx)
//            }
//            responseData.setLength(0)
//            responseData.append(RequestUtils.formatParams(it))
//        }
//        responseData.append(RequestUtils.evaluateDecoderResult(request))
//
//        (msg as? HttpContent)?.also { httpContent ->
//            responseData.append(RequestUtils.formatBody(httpContent))
//            responseData.append(RequestUtils.evaluateDecoderResult(request))
//
//            (httpContent as? LastHttpContent)?.also { lastResponse ->
//                responseData.append(RequestUtils.prepareLastResponse(request, lastResponse))
//                writeResponse(ctx, lastResponse, responseData)
//            }
//        }
//        } ?: kotlin.run {
//            super.channelRead(ctx, msg)
//        }
//    }

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
