package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathMoved
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.telemetry.use
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.otel.get
import io.opentelemetry.api.trace.Span
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.util.zip.GZIPOutputStream

public suspend fun ServerRuntime.handle(request: HttpRequest<PathSpec>): HttpResponse {
    return try {
        server.compiledHttpInterceptors.intercept(request) { req ->
            this.logger.info { "${request.path} accessed by ${request.sourceIp}" }
            var result = try {
                @Suppress("UNCHECKED_CAST")
                (req.path.match.value as HttpHandler<PathSpec>).handleWithMetrics(req as HttpRequest<PathSpec>)
            } catch (notFound: RouteNotFoundException) {
                when (req.path.method) {
                    HttpMethod.HEAD -> {
                        // OK, we'll do a get and remove the body.
                        val getRequest = req.copyWithNewPathType(path = req.path.copy(method = HttpMethod.GET))

                        @Suppress("UNCHECKED_CAST")
                        val getResult =
                            (getRequest.path.match.value as HttpHandler<PathSpec>).handleWithMetrics(getRequest)
                        getResult.copy(
                            body = null,
                            status = if (getResult.status.success) HttpStatus.NoContent else getResult.status,
                        )
                    }

                    else -> {
                        this.logger.debug {
                            "Not found: ${req.path.pathSegments.segments.map { "'$it'" }}, looking for slashes"
                        }
                        if (request.path.pathSegments.isNotEmpty()) {
                            // Let's see if they just got their ending slash wrong.
                            val altSlashEndpoint = req.path.copy(pathSegments = req.path.pathSegments.segments.let {
                                if (it.lastOrNull() == "") it.dropLast(1) else it + ""
                            }.let(::PathSegments))
                            try {
                                altSlashEndpoint.match
                                HttpResponse.pathMoved(to = "/" + altSlashEndpoint.pathSegments.toString())
                            } catch (_: RouteNotFoundException) {
                                throw notFound
                            }
                        } else throw notFound
                    }
                }
            }
            if (result.body == null || request.headers[HttpHeader.AcceptEncoding] == null) return@intercept result

            val acceptedEncodings = request.headers.getMany(HttpHeader.AcceptEncoding)
            if (acceptedEncodings.isEmpty()) return@intercept result

            val accepts = acceptedEncodings
                .map { it.root.lowercase().substringBefore(';').trim() }

            // Accept-Encoding negotiation (gzip only for now)
            if (!accepts.contains("gzip")) return@intercept result

            // Content-Type denylist (skip already-compressed types)
            if (result.body.mediaType.type in setOf("image", "audio", "video") ||
                (result.body.mediaType.type == "application" &&
                        result.body.mediaType.subtype in
                        setOf("zip", "gzip", "x-gzip", "x-7z-compressed", "x-bzip2", "x-tar", "pdf")
                        ) ||
                (result.body.mediaType.type == "font" && result.body.mediaType.subtype in setOf("woff", "woff2"))
            ) return@intercept result

            // Lower compress limit. Either not worth the effort, or likely will inflate a little.
            if (result.body.data.size != -1L && result.body.data.size < 256 /*256 bytes*/) return@intercept result

            val (newData, compressed) = when (val data = result.body.data) {
                is Data.Sink -> {
                    Data.Sink { outSink ->
                        data.write(GZIPOutputStream(outSink.asOutputStream()).asSink().buffered())
                    } to true
                }

                is Data.Source -> {
                    Data.Sink { outSink ->
                        GZIPOutputStream(outSink.asOutputStream()).asSink().buffered().use { gzOut ->
                            data.write(gzOut)
                        }
                    } to true
                }

                else -> {
                    // 1024 Grey area. It likely will compress fine, but if not send the original
                    if (data.size <= 1024 /*1 kibibyte*/) {
                        val og = data.bytes()
                        val gz = og.gzip()
                        if (gz.size < data.size)
                            Data.Bytes(gz) to true
                        else
                            Data.Bytes(og) to false
                    } else
                        Data.Bytes(data.bytes().gzip()) to true
                }
            }
            result.copy(
                headers = if (compressed) result.headers.copy {
                    set(HttpHeader.ContentEncoding, "gzip")
                } else result.headers,
                body = TypedData(newData, result.body.mediaType)
            )
        }
    } catch (e: Exception) {
        try {
            this.logger.error(e) { "Exception in HTTP" }
            instrument("exceptionHandler") {
                server.exceptionHandler.handle(
                    request,
                    e
                )
            }
        } catch (e: Exception) {
            HttpResponse(status = HttpStatus.InternalServerError)
        }
    }
}

context(serverRuntime: ServerRuntime) private suspend inline fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(
    request: HttpRequest<PATH>,
): HttpResponse {
    return instrument(location.toString()) { span ->
        // Add useful HTTP attributes to the current span
        span?.setAttribute("http.method", request.path.method.toString())
        span?.setAttribute("http.route", location.toString())
        span?.setAttribute("http.target", "/" + request.path.pathSegments.toString())
        span?.setAttribute("http.scheme", request.protocol)
        span?.setAttribute("http.host", request.domain)
        span?.setAttribute("net.peer.ip", request.sourceIp)
        val response = this@handleWithMetrics.handle(request)
        span?.setAttribute("http.status_code", response.status.code.toLong())
        response
    }
}


public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(
    location: PATH,
    serverRuntime: ServerRuntime,
    request: WebSocketConnectRequest<PATH>,
): STORAGE {
    return with(serverRuntime) {
        instrument("WEBSOCKET.WILLCONNECT $location") { span ->
            span?.setAttribute("ws.event", "willConnect")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            willConnect(request)
        }
    }
}

public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
) {
    return with(connection) {
        instrument("WEBSOCKET.DIDCONNECT $location") { span ->
            span?.setAttribute("ws.event", "didConnect")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            didConnect()
        }
    }
}

public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
) {
    return with(connection) {
        instrument("WEBSOCKET.MESSAGE $location") { span ->
            span?.setAttribute("ws.event", "messageFromClient")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            span?.setAttribute(
                "ws.frame.type", when (frame) {
                    is WebSocketFrame.Text -> "text"
                    is WebSocketFrame.Binary -> "binary"
                }
            )
            span?.setAttribute(
                "ws.frame.size", when (frame) {
                    is WebSocketFrame.Text -> frame.content.length.toLong()
                    is WebSocketFrame.Binary -> frame.content.size.toLong()
                }
            )
            messageFromClient(frame)
        }
    }
}

public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
) {
    return with(connection) {
        instrument("WEBSOCKET.SUBSCRIPTION $location") { span ->
            span?.setAttribute("ws.event", "messageFromSubscription")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            span?.setAttribute("ws.subscription.topic", topic.topic.location.toString())
            messageFromSubscription(topic)
        }
    }
}

public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
) {
    return with(connection) {
        instrument("WEBSOCKET.DISCONNECT $location") { span ->
            span?.setAttribute("ws.event", "disconnect")
            span?.setAttribute("ws.route", location.toString())
            span?.setAttribute("net.peer.ip", request.sourceIp)
            span?.setAttribute("ws.disconnect.code", reason.code.toLong())
            span?.setAttribute("ws.disconnect.reason", reason.name)
            disconnect(reason)
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun <T> Task<T>.executeWithMetrics(location: PathSpec0, input: T) {
    return instrument("TASK $location") { span ->
        span?.setAttribute("task.type", "TASK")
        span?.setAttribute("task.route", location.toString())
        with(serverRuntime) {
            this@executeWithMetrics.executeInline(input)
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0) {
    return instrument("SCHEDULE $location") { span ->
        span?.setAttribute("task.type", "SCHEDULE")
        span?.setAttribute("task.route", location.toString())
        with(serverRuntime) {
            this@executeWithMetrics.execute()
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun StartupTask.executeWithMetrics(location: PathSpec0) {
    return instrument("STARTUP $location") { span ->
        span?.setAttribute("task.type", "STARTUP")
        span?.setAttribute("task.route", location.toString())
        execute()
    }
}

context(runtime: ServerRuntime)
public suspend inline fun <T> instrument(name: String, crossinline action: suspend (Span?) -> T): T {
    val tel = runtime.openTelemetry?.get("com.lightningkite.lightningserver")
    return if (tel != null) tel.spanBuilder(name).use {
        try {
            action(it)
        } catch (t: Throwable) {
            tel.error("Context $name failed", t)
            throw t
        }
    } else action(null)
}