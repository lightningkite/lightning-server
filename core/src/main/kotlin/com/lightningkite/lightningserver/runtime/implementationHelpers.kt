package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.handleInstrumented
import com.lightningkite.lightningserver.pathMoved
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.telemetry.use
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.Data
import com.lightningkite.services.otel.get
import io.opentelemetry.api.trace.Span
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

public suspend fun ServerRuntime.handle(request: HttpRequest<PathSpec>): HttpResponse {
    return try {
        server.compiledHttpInterceptors.handle(request) { req ->
            var result = try {
                @Suppress("UNCHECKED_CAST")
                (req.path.match.value as HttpHandler<PathSpec>).handleWithMetrics(req as HttpRequest<PathSpec>)
            } catch(notFound: RouteNotFoundException) {
                when (req.path.method) {
                    HttpMethod.OPTIONS -> {
                        // Let's return the available methods.
                        val perEndpoint = listOf(
                            HttpMethod.GET,
                            HttpMethod.POST,
                            HttpMethod.PUT,
                            HttpMethod.PATCH,
                            HttpMethod.DELETE,
                            HttpMethod.OPTIONS,
                            HttpMethod.HEAD,
                        ).associateWith { method ->
                            serverRuntime.server.endpoints.match(
                                serverRuntime.externalSerialization.stringArrayFormat,
                                request.path.asString
                            ) { it.http[method] }
                        }
                        val existingMethods = perEndpoint.entries.filter { it.value != null }.map { it.key }
                        HttpResponse(
                            status = if(existingMethods.isEmpty()) HttpStatus.NotFound else HttpStatus.NoContent,
                            headers = HttpHeaders {
                                set(HttpHeader.AccessControlAllowMethods, existingMethods.joinToString(","))
                            }
                        )
                    }
                    HttpMethod.HEAD -> {
                        // OK, we'll do a get and remove the body.
                        val getRequest = req.copyWithNewPathType(path = req.path.copy(method = HttpMethod.GET))

                        @Suppress("UNCHECKED_CAST")
                        val getResult = (getRequest.path.match.value as HttpHandler<PathSpec>).handleWithMetrics(getRequest)
                        getResult.copy(
                            body = null,
                            status = if(getResult.status.success) HttpStatus.NoContent else getResult.status,
                        )
                    }
                    else -> {
                        // Let's see if they just got their ending slash wrong.
                        val altSlashEndpoint = req.path.copy(asString = req.path.asString.let {
                            if(it.endsWith('/')) it.substringBeforeLast('/') else "$it/"
                        })
                        try {
                            altSlashEndpoint.match
                            HttpResponse.pathMoved(to = altSlashEndpoint.asString)
                        } catch(_: RouteNotFoundException) {
                            throw notFound
                        }
                    }
                }
            }
            // Compression
            run {
                for(option in request.headers.getMany(HttpHeader.AcceptEncoding).map { it.root }) {
                    when(option) {
                        "gzip" -> {
                            result = result.copy(
                                headers = result.headers + HttpHeaders(HttpHeader.ContentEncoding to "gzip"),
                                body = result.body?.copy(
                                    data = when(val data = result.body.data) {
                                        is Data.Sink -> Data.Sink(
                                            emit = {
                                                GZIPOutputStream(it.asOutputStream()).asSink().buffered().use {
                                                    data.emit(it)
                                                }
                                            }
                                        )
//                                        is Data.Source -> {}
                                        else -> data.bytes().gzip().let(Data::Bytes)
                                    }
                                )
                            )
                            break
                        }
                    }
                }
            }
            result
        }
    } catch (e: Exception) {
        try {
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
context(serverRuntime: ServerRuntime) private suspend inline fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(request: HttpRequest<PATH>): HttpResponse {
    return instrument(location.toString()) {
        this@handleWithMetrics.handle(
            request
        )
    }
}



public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(location: PATH, serverRuntime: ServerRuntime, request: WebSocketConnectRequest<PATH>): STORAGE {
    return with(serverRuntime) {
        instrument("WEBSOCKET.WILLCONNECT $location") {
            willConnect(request)
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, ) {
    return with(connection) {
        instrument("WEBSOCKET.DIDCONNECT $location") {

            didConnect()
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame) {
    return with(connection) {
        instrument("WEBSOCKET.MESSAGE $location") {

            messageFromClient(frame)
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>) {
    return with(connection) {
        instrument("WEBSOCKET.SUBSCRIPTION $location") {

            messageFromSubscription(topic)
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose) {
    return with(connection) {
        instrument("WEBSOCKET.DISCONNECT $location") {

            disconnect(reason)
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun <T> Task<T>.executeWithMetrics(location: PathSpec0, input: T) {
    return instrument("TASK $location") {

        with(serverRuntime) {
            this@executeWithMetrics.execute(input)
        }
    }
}
context(serverRuntime: ServerRuntime)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0, ) {
    return instrument("SCHEDULE $location") {

        with(serverRuntime) {
            this@executeWithMetrics.execute()
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun StartupTask.executeWithMetrics(location: PathSpec0) {
    return instrument("STARTUP $location") {

        execute()
    }
}

context(runtime: ServerRuntime)
public suspend inline fun <T> instrument(name: String, crossinline action: suspend (Span?) -> T): T {
    val tel = runtime.openTelemetry?.get("com.lightningkite.lightningserver")
    return if(tel != null) tel.spanBuilder(name).use {
        try {
            action(it)
        } catch(t: Throwable) {
            tel.error("Context $name failed", t)
            throw t
        }
    } else action(null)
}