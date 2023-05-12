package com.lightningkite.lightningserver.http4k

import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.websockets
import org.http4k.server.*
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage

fun runServer(config: PolyServerConfig) {

    val http: (Request) -> Response = { request: Request ->
        println("Path: ${request.uri.path}")
        println("Query: ${request.uri.query}")
        println(request.uri)
        println(request.method)
        println(request.headers)


        Response(status = Status.OK).body("Hello, ${request.query("name")}!")
    }
    val ws = websockets(
        "/*" bind { ws: Websocket ->
            println("Socket Opened")
            ws.send(WsMessage("Websocket Connected"))
            ws.onMessage {
                println("Message: ${it.bodyString()}")
                ws.send(WsMessage(it.body))
            }
            ws.onClose {
                println("Websocket Disconnecting")
            }
        }
    )
    val service = PolyHandler(http, ws).asServer(config)
    service.start()

    service.block()
}

fun runUndertow(){
    runServer(Undertow(9001))
}

fun runJetty(){
    runServer(Jetty(9002))
}