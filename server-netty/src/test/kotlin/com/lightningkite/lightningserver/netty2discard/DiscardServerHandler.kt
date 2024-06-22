package com.lightningkite.lightningserver.netty2discard

import com.lightningkite.now
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import kotlinx.datetime.Clock


class DiscardServerHandler : ChannelInboundHandlerAdapter() {

    override fun channelActive(ctx: ChannelHandlerContext) {
        val time = ctx.alloc().buffer(4)
        time.writeBytes((now()).toString().encodeToByteArray())

        val f = ctx.writeAndFlush(time)
        f.addListener { future ->
            assert(f == future)
            ctx.close()
        }
    }

    // (1)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { // (2)
        // Discard the received data silently.
        ctx.write(msg)
        ctx.flush()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { // (4)
        // Close the connection when an exception is raised.
        cause.printStackTrace()
        ctx.close()
    }
}
