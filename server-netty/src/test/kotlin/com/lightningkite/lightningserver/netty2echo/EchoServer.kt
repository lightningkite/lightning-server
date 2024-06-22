package com.lightningkite.lightningserver.netty2echo

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.*
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.AsciiString


open class HttpRequestDecoder : HttpObjectDecoder {

    constructor(maxInitialLineLength: Int, maxHeaderSize: Int, maxChunkSize: Int) : this(
        HttpDecoderConfig().setMaxInitialLineLength(maxInitialLineLength).setMaxHeaderSize(maxHeaderSize)
            .setMaxChunkSize(maxChunkSize)
    )


    @Deprecated("")
    constructor(maxInitialLineLength: Int, maxHeaderSize: Int, maxChunkSize: Int, validateHeaders: Boolean) : super(
        maxInitialLineLength,
        maxHeaderSize,
        maxChunkSize,
        true,
        validateHeaders
    )


    @Deprecated("")
    constructor(
        maxInitialLineLength: Int,
        maxHeaderSize: Int,
        maxChunkSize: Int,
        validateHeaders: Boolean,
        initialBufferSize: Int,
    ) : super(maxInitialLineLength, maxHeaderSize, maxChunkSize, true, validateHeaders, initialBufferSize)


    @Deprecated("")
    constructor(
        maxInitialLineLength: Int,
        maxHeaderSize: Int,
        maxChunkSize: Int,
        validateHeaders: Boolean,
        initialBufferSize: Int,
        allowDuplicateContentLengths: Boolean,
    ) : super(
        maxInitialLineLength,
        maxHeaderSize,
        maxChunkSize,
        true,
        validateHeaders,
        initialBufferSize,
        allowDuplicateContentLengths
    )


    @Deprecated("")
    constructor(
        maxInitialLineLength: Int,
        maxHeaderSize: Int,
        maxChunkSize: Int,
        validateHeaders: Boolean,
        initialBufferSize: Int,
        allowDuplicateContentLengths: Boolean,
        allowPartialChunks: Boolean,
    ) : super(
        maxInitialLineLength,
        maxHeaderSize,
        maxChunkSize,
        true,
        validateHeaders,
        initialBufferSize,
        allowDuplicateContentLengths,
        allowPartialChunks
    )

    constructor(config: HttpDecoderConfig?) : super(config)

    @Throws(java.lang.Exception::class)
    override fun createMessage(initialLine: Array<String>): HttpMessage {

        return DefaultHttpRequest(
            HttpVersion.valueOf(
                initialLine[2]
            ), HttpMethod.valueOf(initialLine[0]), initialLine[1], this.headersFactory
        )
    }

    override fun splitHeaderName(sb: ByteArray, start: Int, length: Int): AsciiString {
        val firstChar = sb[start]
        if (firstChar.toInt() == 72) {
            if (length == 4 && isHost(sb, start)) {
                return Host
            }
        } else if (firstChar.toInt() == 65) {
            if (length == 6 && isAccept(sb, start)) {
                return Accept
            }
        } else if (firstChar.toInt() == 67) {
            if (length == 10) {
                if (isConnection(sb, start)) {
                    return Connection
                }
            } else if (length == 12) {
                if (isContentType(sb, start)) {
                    return ContentType
                }
            } else if (length == 14 && isContentLength(sb, start)) {
                return ContentLength
            }
        }

        return super.splitHeaderName(sb, start, length)
    }

    override fun splitFirstWordInitialLine(sb: ByteArray, start: Int, length: Int): String {
        if (length == 3) {
            if (isGetMethod(sb, start)) {
                return HttpMethod.GET.name()
            }
        } else if (length == 4 && isPostMethod(sb, start)) {
            return HttpMethod.POST.name()
        }

        return super.splitFirstWordInitialLine(sb, start, length)
    }

    override fun splitThirdWordInitialLine(sb: ByteArray, start: Int, length: Int): String {
        if (length == 8) {
            val maybeHttp1_x =
                (sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)).toLong() or (sb[start + 4].toLong() shl 32) or (sb[start + 5].toLong() shl 40) or (sb[start + 6].toLong() shl 48) or (sb[start + 7].toLong() shl 56)
            if (maybeHttp1_x == 3543824036068086856L) {
                return "HTTP/1.1"
            }

            if (maybeHttp1_x == 3471766442030158920L) {
                return "HTTP/1.0"
            }
        }

        return super.splitThirdWordInitialLine(sb, start, length)
    }

    override fun createInvalidMessage(): HttpMessage {
        return DefaultFullHttpRequest(
            HttpVersion.HTTP_1_0, HttpMethod.GET, "/bad-request", Unpooled.buffer(0),
            this.headersFactory,
            this.trailersFactory
        )
    }

    override fun isDecodingRequest(): Boolean {
        return true
    }

    override fun isContentAlwaysEmpty(msg: HttpMessage): Boolean {
        return if (msg.javaClass == DefaultHttpRequest::class.java) false else super.isContentAlwaysEmpty(msg)
    }

    companion object {
       val Accept: AsciiString = AsciiString.cached("Accept")
       val Host: AsciiString = AsciiString.cached("Host")
       val Connection: AsciiString = AsciiString.cached("Connection")
       val ContentType: AsciiString = AsciiString.cached("Content-Type")
       val ContentLength: AsciiString = AsciiString.cached("Content-Length")
       const val GET_AS_INT = 5522759
       const val POST_AS_INT = 1414745936
       const val HTTP_1_1_AS_LONG = 3543824036068086856L
       const val HTTP_1_0_AS_LONG = 3471766442030158920L
       const val HOST_AS_INT = 1953722184
       const val CONNECTION_AS_LONG_0 = 7598807758576447299L
       const val CONNECTION_AS_SHORT_1: Short = 28271
       const val CONTENT_AS_LONG = 3275364211029339971L
       const val TYPE_AS_INT = 1701869908
       const val LENGTH_AS_LONG = 114849160783180L
       const val ACCEPT_AS_LONG = 128026086171457L

        fun isAccept(sb: ByteArray, start: Int): Boolean {
            val maybeAccept =
                (sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)).toLong() or (sb[start + 4].toLong() shl 32) or (sb[start + 5].toLong() shl 40)
            return maybeAccept == 128026086171457L
        }

        fun isHost(sb: ByteArray, start: Int): Boolean {
            val maybeHost =
                sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)
            return maybeHost == 1953722184
        }

        fun isConnection(sb: ByteArray, start: Int): Boolean {
            val maybeConnecti =
                (sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)).toLong() or (sb[start + 4].toLong() shl 32) or (sb[start + 5].toLong() shl 40) or (sb[start + 6].toLong() shl 48) or (sb[start + 7].toLong() shl 56)
            if (maybeConnecti != 7598807758576447299L) {
                return false
            } else {
                val maybeOn = (sb[start + 8].toInt() or (sb[start + 9].toInt() shl 8)).toShort()
                return maybeOn.toInt() == 28271
            }
        }

        private fun isContentType(sb: ByteArray, start: Int): Boolean {
            val maybeContent =
                (sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)).toLong() or (sb[start + 4].toLong() shl 32) or (sb[start + 5].toLong() shl 40) or (sb[start + 6].toLong() shl 48) or (sb[start + 7].toLong() shl 56)
            if (maybeContent != 3275364211029339971L) {
                return false
            } else {
                val maybeType =
                    sb[start + 8].toInt() or (sb[start + 9].toInt() shl 8) or (sb[start + 10].toInt() shl 16) or (sb[start + 11].toInt() shl 24)
                return maybeType == 1701869908
            }
        }

        private fun isContentLength(sb: ByteArray, start: Int): Boolean {
            val maybeContent =
                (sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)).toLong() or (sb[start + 4].toLong() shl 32) or (sb[start + 5].toLong() shl 40) or (sb[start + 6].toLong() shl 48) or (sb[start + 7].toLong() shl 56)
            if (maybeContent != 3275364211029339971L) {
                return false
            } else {
                val maybeLength =
                    (sb[start + 8].toInt() or (sb[start + 9].toInt() shl 8) or (sb[start + 10].toInt() shl 16) or (sb[start + 11].toInt() shl 24)).toLong() or (sb[start + 12].toLong() shl 32) or (sb[start + 13].toLong() shl 40)
                return maybeLength == 114849160783180L
            }
        }

        private fun isGetMethod(sb: ByteArray, start: Int): Boolean {
            val maybeGet = sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16)
            return maybeGet == 5522759
        }

        private fun isPostMethod(sb: ByteArray, start: Int): Boolean {
            val maybePost =
                sb[start].toInt() or (sb[start + 1].toInt() shl 8) or (sb[start + 2].toInt() shl 16) or (sb[start + 3].toInt() shl 24)
            return maybePost == 1414745936
        }
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
            val ssc = SelfSignedCertificate()
            val sslContext = SslContextBuilder
                .forServer(ssc.certificate(), ssc.privateKey())
                .sslProvider(SslProvider.JDK)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(
                    ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1,
                    )
                )
                .build()

            val b = ServerBootstrap() // (2)
//            b.option(ChannelOption.SO_BACKLOG, 1024) // (5)
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java) // (3)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    // (4)
                    @Throws(Exception::class)
                    override fun initChannel(ch: SocketChannel) {
                        println("In Init Channel")
                        if (sslContext != null) {
                            ch.pipeline()
                                .addLast(
                                    sslContext.newHandler(ch.alloc()),
                                    HttpUtil.getServerAPNHandler()
                                )
                        } else {
                            ch.pipeline()
                                .addLast(
                                    HttpRequestDecoder(),
                                    HttpResponseEncoder(),
                                    EchoServerHandler(),
                                )
                        }
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

            EchoServer(port).run()
        }
    }
}