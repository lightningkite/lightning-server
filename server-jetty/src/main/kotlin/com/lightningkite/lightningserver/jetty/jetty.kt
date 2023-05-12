package com.lightningkite.lightningserver.jetty

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.HandlerWrapper
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.Utf8StringBuilder
import org.eclipse.jetty.websocket.core.*
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler


class LightningHttpHandler : HandlerWrapper() {
    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {

        println("This is my custom Handler")
        println(target)
        println(request.method)
        println(request.contentType)
        println(request.headerNames)
        println(request.pathInfo)
        println(request.queryString)
        println(request.parameterMap)

        response.contentType = "application/json";
        response.status = HttpServletResponse.SC_OK;
        response.writer.println("{ \"status\": \"ok\"}");
        baseRequest.isHandled = true
    }

}

class LightningWebSocketFrameHandler(negotiation: WebSocketNegotiation) : FrameHandler {

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
        println(frame.payloadAsUTF8)
            callback.succeeded()
//        } catch (e: Throwable) {
//            websocket?.triggerError(e)
//            callback.failed(e)
//        }
    }

    override fun onOpen(session: CoreSession, callback: Callback) {
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


fun runServer() {

    val server = Server(8080)
    val httpHandler = LightningHttpHandler()
    server.insertHandler(httpHandler)

    val wsHandler = WebSocketUpgradeHandler(WebSocketComponents()).apply {
        addMapping("/*") { negotiation: WebSocketNegotiation ->
            LightningWebSocketFrameHandler(negotiation)
        }
    }

    server.insertHandler(wsHandler)

    server.start()
    server.join()

}
