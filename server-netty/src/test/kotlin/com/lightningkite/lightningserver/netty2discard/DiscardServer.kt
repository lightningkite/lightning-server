package com.lightningkite.lightningserver.netty2discard

import com.lightningkite.lightningserver.netty.EchoServerHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.*
import io.netty.handler.ssl.util.SelfSignedCertificate


/**
 * Discards any incoming data.
 */
class DiscardServer(private val port: Int) {
    @Throws(Exception::class)
    fun run() {
        val bossGroup: EventLoopGroup = NioEventLoopGroup() // (1)
        val workerGroup: EventLoopGroup = NioEventLoopGroup()
        try {

            val b = ServerBootstrap() // (2)
//            b.option(ChannelOption.SO_BACKLOG, 1024) // (5)
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // (3)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    // (4)
                    @Throws(Exception::class)
                    override fun initChannel(ch: SocketChannel) {
                        println("In Init Channel")
                            ch.pipeline()
                                .addLast(
                                    DiscardServerHandler()
                                )
                    }
                })
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

            DiscardServer(port).run()
        }
    }
}