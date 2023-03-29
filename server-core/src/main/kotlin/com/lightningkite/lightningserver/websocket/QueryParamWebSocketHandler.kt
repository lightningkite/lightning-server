package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics

class QueryParamWebSocketHandler(val cache: () -> Cache) : WebSockets.Handler {
    override suspend fun connect(event: WebSockets.ConnectEvent) {
        val other = event.headers["x-path"] ?: event.queryParameter("path")?.substringBefore('?') ?: "/"
        val match =
            WebSockets.matcher.match(other) ?: throw NotFoundException("No web socket handler found for '$other'")
        val otherHandler =
            WebSockets.handlers[match.path] ?: throw NotFoundException("No web socket handler found for '$other'")
        cache().set("${event.id}-path", match.path.toString())
        val fixedQueryParameters = event.queryParameters.mapNotNull {
            if (it.first == "path") {
                if (it.second.contains('?'))
                    it.second.substringAfter('?').substringBefore('=') to it.second.substringAfter('?')
                        .substringAfter('=')
                else
                    null
            } else it
        }
        Metrics.handlerPerformance(WebSockets.HandlerSection(match.path, WebSockets.WsHandlerType.CONNECT)) {
            otherHandler.connect(
                WebSockets.ConnectEvent(
                    path = match.path,
                    parts = match.parts,
                    wildcard = match.wildcard,
                    queryParameters = fixedQueryParameters,
                    id = event.id,
                    headers = event.headers,
                    domain = event.domain,
                    protocol = event.protocol,
                    sourceIp = event.sourceIp
                )
            )
        }
    }

    override suspend fun message(event: WebSockets.MessageEvent) {
        val path = ServerPath(
            cache().get<String>("${event.id}-path")
                ?: throw NotFoundException("No socket path with id ${event.id} found")
        )
        val otherHandler =
            WebSockets.handlers[path] ?: throw NotFoundException("No web socket handler found for '$path'")
        val section = WebSockets.HandlerSection(path, WebSockets.WsHandlerType.MESSAGE)
        Metrics.handlerPerformance(section) {
            try {
                otherHandler.message(
                    WebSockets.MessageEvent(
                        id = event.id,
                        content = event.content
                    )
                )
            } catch (e: Exception) {
                e.report(section)
                event.id.close()
            }
        }
    }

    override suspend fun disconnect(event: WebSockets.DisconnectEvent) {
        val path = ServerPath(
            cache().get<String>("${event.id}-path")
                ?: throw NotFoundException("No socket path with id ${event.id} found")
        )
        val otherHandler =
            WebSockets.handlers[path] ?: throw NotFoundException("No web socket handler found for '$path'")
        Metrics.handlerPerformance(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.DISCONNECT)) {
            otherHandler.disconnect(
                WebSockets.DisconnectEvent(
                    id = event.id
                )
            )
        }
    }
}