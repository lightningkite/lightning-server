package com.lightningkite.lightningserver.jetty

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler


class LightningHandler: AbstractHandler() {
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
        response.status = HttpServletResponse.SC_NOT_FOUND;
        response.writer.println("{ \"status\": \"ok\"}");
        baseRequest.isHandled = true
    }

}



fun runServer() {
    val server = Server(8080)
    val handler = LightningHandler()
    server.handler = handler

    server.start()
    server.join()

}
