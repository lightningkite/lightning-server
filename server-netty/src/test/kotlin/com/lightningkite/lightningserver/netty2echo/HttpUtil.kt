package com.lightningkite.lightningserver.netty2echo

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http2.*
import io.netty.handler.ssl.*


object HttpUtil {
    fun getServerAPNHandler(): ApplicationProtocolNegotiationHandler {
        val serverAPNHandler: ApplicationProtocolNegotiationHandler = object : ApplicationProtocolNegotiationHandler(
            ApplicationProtocolNames.HTTP_2
        ) {
            @Throws(java.lang.Exception::class)
            override fun configurePipeline(ctx: ChannelHandlerContext, protocol: String) {
                if (ApplicationProtocolNames.HTTP_2 == protocol) {
                    ctx.pipeline()
                        .addLast(
                            Http2FrameCodecBuilder.forServer()
                                .build(), EchoServerHandler()
                        )
                    return
                }
                throw IllegalStateException("Protocol: $protocol not supported")
            }
        }
        return serverAPNHandler
    }
}