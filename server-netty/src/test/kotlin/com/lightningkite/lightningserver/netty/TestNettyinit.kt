package com.lightningkite.lightningserver.netty

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.codec.ReplayingDecoder
import io.netty.handler.codec.http.*
import java.nio.charset.Charset
import java.util.*


data class RequestData(
    var intValue: Int = 0,
    var stringValue: String = "",
)

data class ResponseData(
    val intValue: Int,
)

class RequestDecoder : ReplayingDecoder<RequestData>() {
    private val charset: Charset = Charset.forName("UTF-8")

    @Throws(java.lang.Exception::class)
    override fun decode(
        ctx: ChannelHandlerContext,
        incoming: ByteBuf,
        outgoing: MutableList<Any>,
    ) {
        val data = RequestData()
        data.intValue = incoming.readInt()
        val stringLength = incoming.readInt()
        data.stringValue =
            incoming.readCharSequence(stringLength, charset).toString()
        outgoing.add(data)
    }
}

class ResponseDataEncoder : MessageToByteEncoder<ResponseData>() {
    @Throws(java.lang.Exception::class)
    override fun encode(
        ctx: ChannelHandlerContext,
        msg: ResponseData,
        out: ByteBuf,
    ) {
        out.writeInt(msg.intValue)
    }
}

/**
 * Handles a server-side channel.
 */
class ProcessingHandler : ChannelInboundHandlerAdapter() {
//    var tmp: ByteBuf? = null
//
//    override fun handlerAdded(ctx: ChannelHandlerContext) {
//        println("Handler Added")
//        tmp = ctx.alloc().buffer(4)
//    }
//
//    override fun handlerRemoved(ctx: ChannelHandlerContext) {
//        println("Handler Removed")
//        tmp?.release()
//        tmp = null
//    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {

        if (msg !is RequestData) return
        val response = ResponseData(
            intValue = msg.intValue * 2
        )
        val future = ctx.writeAndFlush(response)
        future.addListener(ChannelFutureListener.CLOSE)
        println(msg)
    }

}

/**
 * Discards any incoming data.
 */
class Server2(private val port: Int) {
    @Throws(Exception::class)
    fun run() {
        val bossGroup: EventLoopGroup = NioEventLoopGroup() // (1)
        val workerGroup: EventLoopGroup = NioEventLoopGroup()
        try {
            val b = ServerBootstrap() // (2)
            b
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // (3)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    // (4)
                    @Throws(Exception::class)
                    override fun initChannel(ch: SocketChannel) {
                        println("In Init Channel")
                        ch.pipeline()
                            .addLast(
                                RequestDecoder(),
                                ResponseDataEncoder(),
                                ProcessingHandler(),
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

    companion object{
        @JvmStatic
        fun main(args: Array<String>) {
            Server2(8080).run()
        }
    }

}




class RequestDataEncoder : MessageToByteEncoder<RequestData>() {
    private val charset: Charset = Charset.forName("UTF-8")

    @Throws(java.lang.Exception::class)
     override fun encode(
        ctx: ChannelHandlerContext,
        msg: RequestData,
        out: ByteBuf,
    ) {
        out.writeInt(msg.intValue)
        out.writeInt(msg.stringValue.length)
        out.writeCharSequence(msg.stringValue, charset)
    }
}

class ResponseDataDecoder : ReplayingDecoder<ResponseData>() {
    @Throws(java.lang.Exception::class)
    override fun decode(
        ctx: ChannelHandlerContext,
        incoming: ByteBuf,
        outgoing: MutableList<Any>,
    ) {
        val data = ResponseData(incoming.readInt())
        outgoing.add(data)
    }
}

class ClientHandler : ChannelInboundHandlerAdapter() {
    @Throws(Exception::class)
    override fun channelActive(ctx: ChannelHandlerContext) {
        val msg = RequestData()
        msg.intValue = 123
        msg.stringValue = "Who is there?"
        val future = ctx.writeAndFlush(msg)
    }

    @Throws(java.lang.Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        println(msg as ResponseData)
        ctx.close()
    }
}

object NettyClient {
    @Throws(java.lang.Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val host = "localhost"
        val port = 8080
        val workerGroup: EventLoopGroup = NioEventLoopGroup()

        try {
            val b: Bootstrap = Bootstrap()
            b.group(workerGroup)
            b.channel(NioSocketChannel::class.java)
            b.option(ChannelOption.SO_KEEPALIVE, true)
            b.handler(object : ChannelInitializer<SocketChannel>() {
                @Throws(java.lang.Exception::class)
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        RequestDataEncoder(),
                        ResponseDataDecoder(), ClientHandler()
                    )
                }
            })

            val f: ChannelFuture = b.connect(host, port).sync()

            f.channel().closeFuture().sync()
        } finally {
            workerGroup.shutdownGracefully()
        }
    }
}