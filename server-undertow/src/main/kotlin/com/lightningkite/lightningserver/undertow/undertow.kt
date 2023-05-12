package com.lightningkite.lightningserver.undertow

import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import io.undertow.websockets.WebSocketProtocolHandshakeHandler
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange



fun main(vararg args: String) {

    val wsHandler = WebSocketProtocolHandshakeHandler({ exchange: WebSocketHttpExchange, channel: WebSocketChannel ->



    })

    val httpHandler = object: HttpHandler{
        override fun handleRequest(exchange: HttpServerExchange) {
            exchange.responseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
            exchange.setStatusCode(StatusCodes.OK)
            exchange.responseSender.send("Hello World2")
        }

    }
    val server = Undertow.builder()
        .addHttpListener(8080, "localhost")
        .setHandler(Handlers.predicate(requiresWebSocketUpgrade(), wsHandler, httpHandler))
        .build()
    server.start()
}

// I pulled this from http4k
fun requiresWebSocketUpgrade(): (HttpServerExchange) -> Boolean = { httpServerExchange ->
    val containsValidConnectionHeader = httpServerExchange.requestHeaders["Connection"]
        ?.any { headerValue ->
            headerValue.split(",")
                .map { it.trim().lowercase() }
                .contains("upgrade")
        } ?: false

    val containsValidUpgradeHeader = httpServerExchange.requestHeaders["Upgrade"]
        ?.any { it.equals("websocket", true) } ?: false

    containsValidConnectionHeader && containsValidUpgradeHeader
}
