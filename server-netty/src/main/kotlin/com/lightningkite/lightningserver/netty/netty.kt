package com.lightningkite.lightningserver.netty

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.settings.CorsSettings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.settings.loadSettings
import io.ktor.util.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.cors.CorsConfigBuilder
import io.netty.handler.codec.http.cors.CorsHandler
import kotlinx.coroutines.*
import java.io.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.suspendCoroutine

typealias LightningRequest = com.lightningkite.lightningserver.http.HttpRequest
typealias LightningResponse = com.lightningkite.lightningserver.http.HttpResponse
typealias LightningHeaders = com.lightningkite.lightningserver.http.HttpHeaders
typealias LightningMethod = com.lightningkite.lightningserver.http.HttpMethod

class NettyException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

class LightningServerHandler(override val coroutineContext: CoroutineContext) :
    ChannelInboundHandlerAdapter(), CoroutineScope {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        println("LightningServerHandler Read")
        val request = msg as LightningRequest
        launch {
            val response = Http.execute(request)

            println(response.body != null)
            if (response.body != null) {
                println("LSRequest Encoder IN Body launch")
                val bodyByes = response.body!!.bytes()
                ctx.writeAndFlush(
                    DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(response.status.code),
                        Unpooled.copiedBuffer(bodyByes),
                    )
                        .apply {
                            response.headers.normalizedEntries
                                .forEach { (key, values) ->
                                    this.headers().set(key, values)
                                }
                            this.headers()[HttpHeader.ContentLength] = bodyByes.size
                            this.headers()[HttpHeader.ContentType] = response.body!!.type.type
                        },
                )
            } else {
                println("LSRequest Encoder Direct Write")
                ctx.writeAndFlush(
                    DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(response.status.code),
                    )
                        .apply {
                            response.headers.normalizedEntries
                                .forEach { (key, values) ->
                                    this.headers().set(key, values)
                                }
                            this.headers().remove(HttpHeader.ContentLength)
                        }
                )
            }
        }

    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        println("In Exception Caught")
        launch {
            exceptionSettings().report(NettyException(cause = cause))
        }
        ctx.close()
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        println("Channel Read Complete")
        ctx.flush()
    }

}

class CorsOutboundHeadersHandler(val cors: CorsSettings) : ChannelOutboundHandlerAdapter() {

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        println("Cors Out Write")
        println(msg is LightningResponse)
        println(msg is DefaultFullHttpResponse)
        val response = msg as DefaultFullHttpResponse

//        ctx.write(msg, promise)
        response.headers().set(
            HttpHeader.AccessControlAllowOrigin,
            if (cors.allowedDomains.any { it == "*" }) "*" else cors.allowedDomains.joinToString(", ")
        )
        ctx.write(
            response,
            promise
        )

//        ctx.write(
//            result.copy(
//                headers = HttpHeaders {
//                    set(result.headers)
//                    set(
//                        HttpHeader.AccessControlAllowOrigin,
//                        if (cors.allowedDomains.any { it == "*" }) "*" else cors.allowedDomains.joinToString(", ")
//                    )
//                }
//            ),
//            promise
//        )
    }

}

class CorsInboundHandler(val cors: CorsSettings) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        println("Cors In Read")
        val request = msg as LightningRequest

        if (request.method == com.lightningkite.lightningserver.http.HttpMethod.OPTIONS) {
//            ctx.writeAndFlush(
//                LightningResponse(
//                    body = null,
//                    status = HttpStatus.NoContent,
//                    headers = HttpHeaders {
//                        set(
//                            HttpHeader.AccessControlAllowOrigin,
//                            if (cors.allowedDomains.any { it == "*" }) "*" else cors.allowedDomains.joinToString(", ")
//                        )
//                        set(HttpHeader.AccessControlAllowMethods, "GET,POST,PUT,PATCH,DELETE,HEAD")
//                        set(
//                            HttpHeader.AccessControlAllowHeaders,
//                            if (cors.allowedHeaders.any { it == "*" }) "*" else cors.allowedHeaders.joinToString(", ")
//                        )
//                        set(HttpHeader.AccessControlAllowCredentials, "true")
//                    }
//                )
//            )
            ctx.writeAndFlush(
                DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NO_CONTENT,
                )
                    .apply {
                        headers().set(
                            HttpHeader.AccessControlAllowOrigin,
                            if (cors.allowedDomains.any { it == "*" }) "*" else cors.allowedDomains.joinToString(", ")
                        )
                        headers().set(HttpHeader.AccessControlAllowMethods, "GET,POST,PUT,PATCH,DELETE,HEAD")
                        headers().set(
                            HttpHeader.AccessControlAllowHeaders,
                            if (cors.allowedHeaders.any { it == "*" }) "*" else cors.allowedHeaders.joinToString(", ")
                        )
                        headers().set(HttpHeader.AccessControlAllowCredentials, "true")
                    }
            )

        } else {
            println("Continueing")
            ctx.fireChannelRead(msg)
        }
    }
}

// We are not using the encoder because the launch for retrieving the body is causing a freeze somehow.
//class LSRequestEncoder(override val coroutineContext: CoroutineContext) : ChannelOutboundHandlerAdapter(),
//    CoroutineScope {
//    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
//        println("LSRequest Encoder Write")
//        val response = msg as LightningResponse
//        println(response.body != null)
//        if (response.body != null) {
//            GlobalScope.launch {
//
////            }
////            launch {
//                println("LSRequest Encoder IN Body launch")
//                ctx.write(
//                    DefaultFullHttpResponse(
//                        HttpVersion.HTTP_1_1,
//                        HttpResponseStatus.valueOf(response.status.code),
//                    )
//                        .apply {
//                            response.headers.normalizedEntries
//                                .forEach { (key, values) ->
//                                    this.headers().set(key, values)
//                                }
//                            this.headers().remove(HttpHeader.ContentLength)
//                        },
//                    promise
//                )
////                ctx.write(
////                    DefaultFullHttpResponse(
////                        HttpVersion.HTTP_1_1,
////                        HttpResponseStatus.valueOf(response.status.code),
////                        Unpooled.copiedBuffer(byteArrayOf()),
////                    )
////                        .apply {
////                            response.headers.normalizedEntries
////                                .forEach { (key, values) ->
////                                    this.headers().set(key, values)
////                                }
////                            this.headers()[HttpHeader.ContentLength] = response.body!!.
////                            this.headers()[HttpHeader.ContentType] = response.body!!.type.type
////                        },
////                    promise
////                )
//            }
//        } else {
//            println("LSRequest Encoder Direct Write")
//            ctx.write(
//                DefaultFullHttpResponse(
//                    HttpVersion.HTTP_1_1,
//                    HttpResponseStatus.valueOf(response.status.code),
//                )
//                    .apply {
//                        response.headers.normalizedEntries
//                            .forEach { (key, values) ->
//                                this.headers().set(key, values)
//                            }
//                        this.headers().remove(HttpHeader.ContentLength)
//                    },
//                promise
//            )
//        }
//
//    }
//}

class LSRequestDecoder : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        println("LSRequest Decoder Read")
        val request = msg as FullHttpRequest

        val match = if (request.method() == HttpMethod.OPTIONS) {
            request.headers()[HttpHeader.AccessControlRequestMethod]?.let { method ->
                Http.matcher.match(request.uri(), LightningMethod(method))?.let { match: HttpEndpointMatcher.Match ->
                    match.copy(
                        endpoint = HttpEndpoint(match.endpoint.path, LightningMethod.OPTIONS)
                    )
                }
            }
        } else {
            Http.matcher.match(request.uri(), LightningMethod(request.method().name()))
        }
            ?: run {
                HttpEndpointMatcher.Match(
                    HttpEndpoint(request.uri(), LightningMethod(request.method().name())),
                    emptyMap(),
                    null
                )
            }

        ctx.fireChannelRead(
            LightningRequest(
                endpoint = match.endpoint,
                parts = match.parts,
                wildcard = match.wildcard,
                queryParameters = QueryStringDecoder(request.uri()).parameters()
                    .flatMap { (key, values) -> values.map { key to it } },
                headers = LightningHeaders(request.headers().entries().map { it.key to it.value }),
                body = if (request.content().isReadable) {
                    val type = ContentType(request.headers()[HttpHeader.ContentType])
                    if (request.content().hasArray())
                        HttpContent.Binary(
                            request.content().array(),
                            type
                        ) else
                        HttpContent.Stream(
                            {
                                val bytes = ByteArray(request.content().readableBytes())
                                request.content().readBytes(bytes)
                                ByteArrayInputStream(bytes)
                            },
                            request.content().readableBytes().toLong(),
                            type
                        )
                } else null
            )
        )

    }
}


fun runServer() {
    val bossGroup: EventLoopGroup = NioEventLoopGroup(1) // (1)
    val workerGroup: EventLoopGroup = NioEventLoopGroup(1)
    try {
        val b = ServerBootstrap() // (2)
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java) // (3)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                val cors = generalSettings().cors

                // (4)
                @Throws(Exception::class)
                override fun initChannel(ch: SocketChannel) {

                    ch.pipeline()
                        .addLast("codec", HttpServerCodec())
                        .apply {
                            if (cors != null) {
                                val config = if(cors.allowedDomains.any { it == "*" })
                                    CorsConfigBuilder.forAnyOrigin()
                                else
                                    CorsConfigBuilder.forOrigins(*cors.allowedDomains.toTypedArray())
                                config.allowedRequestHeaders(*cors.allowedHeaders.toTypedArray())
                                addLast("cors", CorsHandler(config.build()))
                            }
                        }
                        .addLast("httpKeepAlive", HttpServerKeepAliveHandler())
                        .addLast("aggregator", HttpObjectAggregator(5_242_880))
                        .addLast("continue", HttpServerExpectContinueHandler())
                        .addLast("decoder", LSRequestDecoder())
//                        .addLast("encoder", LSRequestEncoder(Dispatchers.Default))
                        .addLast("handler", LightningServerHandler(Dispatchers.Default))
                }
            })
            .option<Int>(ChannelOption.SO_BACKLOG, 128) // (5)
//            .childOption<Boolean>(ChannelOption.SO_KEEPALIVE, true) // (6)


        // Bind and start to accept incoming connections.
        val f = b.bind(8080).sync() // (7)


        // Wait until the server socket is closed.
        // In this example, this does not happen, but you can do that to gracefully
        // shut down your server.
        f.channel().closeFuture().sync()
    } finally {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}

fun main() {
    ServerPath.root.path("/get").get.handler { request ->
        val incomingBody = request.body?.text()
        LightningResponse(
            body = incomingBody?.let {
                com.lightningkite.lightningserver.http.HttpContent.Text(
                    buildString {
                        appendLine(incomingBody)
                        request.headers.entries.forEach {
                            appendLine("${it.first}: ${it.second}")
                        }
                    },
                    ContentType.Text.Plain
                )
            },
            status = HttpStatus.OK,
        )
    }
    ServerPath.root.path("/post").post.handler { request ->
        val incomingBody = request.body?.text()
//        println(incomingBody?.decodeToString())
        LightningResponse(
            body = incomingBody?.let {
                com.lightningkite.lightningserver.http.HttpContent.Text(
                    buildString {
                        appendLine(incomingBody)
                        request.headers.entries.forEach {
                            appendLine("${it.first}: ${it.second}")
                        }
                    },
                    ContentType.Text.Plain
                )
            },
            status = HttpStatus.OK,
        )
    }

    loadSettings(File("settings.json"))

    runServer()
}
