package com.lightningkite.lightningserver.jetty

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.cors.extensionForEngineAddCors
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.serialization.toHttpContent
import com.lightningkite.lightningserver.settings.CorsSettings
import com.lightningkite.lightningserver.settings.generalSettings
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.AsyncDelayHandler
import org.eclipse.jetty.server.handler.HandlerWrapper
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.Utf8StringBuilder
import org.eclipse.jetty.util.thread.ThreadPool
import org.eclipse.jetty.websocket.core.*
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler
import java.nio.ByteBuffer
import java.util.concurrent.ThreadPoolExecutor
import kotlin.coroutines.CoroutineContext
import kotlin.io.copyTo


suspend fun HttpResponse.adaptJetty(jettyResponse: HttpServletResponse) {
    for (header in headers.entries) {
        jettyResponse.setHeader(header.first, header.second)
    }
    jettyResponse.status = status.code
    when (val b = body) {
        null -> {}
        is HttpContent.Binary -> jettyResponse.outputStream.write(b.bytes)
        is HttpContent.Text -> jettyResponse.outputStream.write(b.bytes)
        is HttpContent.OutStream -> b.write(jettyResponse.outputStream)
        is HttpContent.Stream -> b.getStream().copyTo(jettyResponse.outputStream)
        is HttpContent.Multipart -> TODO()
    }
    jettyResponse.contentType = body?.type.toString()
}

suspend fun setNotFound(
    target: String,
    baseRequest: Request,
    response: HttpServletResponse
) {
    HttpResponse(
        body = LSError(
            HttpStatus.NotFound.code,
            message = "No matching path for '${target}' found",
        ).toHttpContent(listOf(ContentType(baseRequest.contentType))),
        status = HttpStatus.NotFound,
        headers = HttpHeaders.EMPTY
    )
        .adaptJetty(response)
}

class LightningHttpHandler(
    val engineDispatcher: CoroutineDispatcher,
) : HandlerWrapper(), CoroutineScope {


    override val coroutineContext: CoroutineContext
        get() = TODO("Not yet implemented")

    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {

        runBlocking {
            val method = HttpMethod(baseRequest.method.uppercase())
            val headers = HttpHeaders(
                baseRequest.headerNames.toList()
                    .flatMap { outer -> baseRequest.getHeaders(outer).toList().map { outer to it } })
            Http.matcher.match(target, method)
                ?.let { match ->
                    val lightningRequest = HttpRequest(
                        endpoint = match.endpoint,
                        parts = match.parts,
                        wildcard = match.wildcard,
                        queryParameters = baseRequest.parameterMap.toList()
                            .flatMap { outer -> outer.second.map { outer.first to it.decodeURLPart() } },
                        headers = HttpHeaders(
                            baseRequest.headerNames.toList()
                                .flatMap { outer -> baseRequest.getHeaders(outer).toList().map { outer to it } }),
                        body = HttpContent.Stream(
                            { baseRequest.inputStream },
                            baseRequest.contentLengthLong,
                            ContentType(baseRequest.contentType)
                        ),
                        domain = baseRequest.remoteHost,
                        protocol = baseRequest.scheme,
                        sourceIp = baseRequest.remoteAddr
                    )

                    val result = Http.execute(lightningRequest).extensionForEngineAddCors(lightningRequest)

                    result.adaptJetty(response)

                }
                ?: run {
                    if (method == HttpMethod.OPTIONS) {
                        headers[HttpHeader.Origin]
                            ?.let { origin ->
                                val cors = generalSettings().cors ?: CorsSettings()
                                val matches = cors.allowedDomains.any {
                                    it == "*" || it == origin || origin.endsWith(it.removePrefix("*"))
                                }
                                if (matches) {
                                    HttpResponse(
                                        headers = HttpHeaders(
                                            HttpHeader.AccessControlAllowOrigin to (headers[HttpHeader.Origin] ?: "*"),
                                            HttpHeader.AccessControlAllowMethods to (headers[HttpHeader.AccessControlRequestMethod]
                                                ?: "GET"),
                                            HttpHeader.AccessControlAllowHeaders to cors.allowedHeaders.joinToString(", "),
                                            HttpHeader.AccessControlAllowCredentials to "true",
                                        )
                                    ).adaptJetty(response)
                                } else
                                    setNotFound(target, baseRequest, response)
                            }
                            ?: run { setNotFound(target, baseRequest, response) }
                    } else {
                        setNotFound(target, baseRequest, response)
                    }
                }
        }

    }
}

class LightningWebSocketFrameHandler(negotiation: WebSocketNegotiation) : FrameHandler {

    lateinit var session: CoreSession

    override fun onFrame(frame: Frame, callback: Callback) {
//        try {
//            when (frame.opCode) {
//                OpCode.TEXT, OpCode.BINARY, OpCode.CONTINUATION -> {
//                    textBuffer.append(frame.payload)
//
//                    if (frame.isFin) {
//                        websocket?.triggerMessage(WsMessage(Body(textBuffer.toString())))
//                        textBuffer.reset()
//                    }
//                }
//            }
        val incomingMessage = frame.payloadAsUTF8
        println(frame.payloadAsUTF8)
//        callback.completeWith()
        callback.succeeded()
        session.sendFrame(
            Frame(
                OpCode.TEXT,
                incomingMessage
            ),
            object : Callback {},
            false,
        )
//        } catch (e: Throwable) {
//            websocket?.triggerError(e)
//            callback.failed(e)
//        }
    }

    override fun onOpen(session: CoreSession, callback: Callback) {
        this.session = session
//        websocket = object : PushPullAdaptingWebSocket(upgradeRequest) {
//            override fun send(message: WsMessage) {
//                session.sendFrame(
//                    Frame(
//                    if (message.body is StreamBody) OpCode.BINARY else OpCode.TEXT,
//                    message.body.payload), object : Callback {
//                    override fun succeeded() = session.flush(object : Callback {})
//                }, false)
//            }
//
//            override fun close(status: WsStatus) {
//                session.close(status.code, status.description, object : Callback {
//                    override fun succeeded() = session.flush(object : Callback {})
//                })
//            }
//        }.apply(wSocket)
        callback.succeeded()
    }

    override fun onError(cause: Throwable, callback: Callback) {
//        websocket?.triggerError(cause)
        callback.succeeded()
    }

    override fun onClosed(closeStatus: CloseStatus, callback: Callback) {
//        websocket?.triggerClose(WsStatus(closeStatus.code, closeStatus.reason ?: "<unknown>"))
    }
}

@Serializable
data class TempThing(
    val id: Int,
    val path: String
)

fun runServer() {


    val server = Server()
    server.threadPool.asCoroutineDispatcher()

    server.connectors = arrayOf(ServerConnector(server).apply {
        port = 8941
        this.acceptQueueSize
    })
    val httpHandler = LightningHttpHandler(server.threadPool.asCoroutineDispatcher())
    server.insertHandler(httpHandler)

    val wsHandler = WebSocketUpgradeHandler(WebSocketComponents()).apply {
        addMapping("/*") { negotiation: WebSocketNegotiation ->
            LightningWebSocketFrameHandler(negotiation)
        }
    }

    server.insertHandler(wsHandler)

    Http.endpoints[HttpEndpoint(
        "index",
        HttpMethod.GET
    )] = { request ->
        HttpResponse(HttpContent.json(TempThing(22, request.wildcard ?: "")))
    }

    server.start()
    server.join()

}
