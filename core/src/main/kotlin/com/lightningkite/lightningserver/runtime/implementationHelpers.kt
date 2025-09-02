package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.RouteNotFoundException
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.exceptionSettings
import com.lightningkite.lightningserver.definition.metricsSettings
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.topLevelReportingContext

public suspend fun ServerRuntime.handle(request: HttpRequest<PathSpec>): HttpResponse {
    val match = this.server.endpoints.match(externalSerialization.stringArrayFormat, request.path.string) { it.http[request.method] }
        ?: run {
            println("NO match found")
            try {
                return@handle topLevelReportingContext("exceptionHandler") {
                    this.server.exceptionHandler.handle(
                        request.castPathSpec0(),
                        RouteNotFoundException(request.path)
                    )
                }
            } catch (e: Exception) {
                println("exception handler fail $e")
                try {
                    return@handle topLevelReportingContext("exceptionHandler") {
                        this.server.exceptionHandler.handle(
                            request.castPathSpec0(),
                            e
                        )
                    }
                } catch (e: Exception) {
                    return@handle HttpResponse(status = HttpStatus.InternalServerError)
                }
            }
        }
    val properRequest = HttpRequest(
        path = RawPath(asString = request.path.string, match = match),
        queryParameters = request.queryParameters,
        headers = request.headers,
        domain = request.domain,
        protocol = request.protocol,
        sourceIp = request.sourceIp,
        method = request.method,
        cache = request.cache,
        body = request.body,
    )
    val handler = match.value
    @Suppress("UNCHECKED_CAST")
    handler as HttpHandler<PathSpec>
    return try {
        topLevelReportingContext(match.path.toString()) { handler.handle(properRequest) }
    } catch (e: Exception) {
        try {
            topLevelReportingContext("exceptionHandler") {
                this.server.exceptionHandler.handle(request.castPathSpec0(), e)
            }
        } catch (e: Exception) {
            HttpResponse(status = HttpStatus.InternalServerError)
        }
    }
}

internal fun HttpRequest<*>.castPathSpec0(): HttpRequest<PathSpec0> = HttpRequest(
    path = RawPath(path.string),
    queryParameters = queryParameters,
    headers = headers,
    domain = domain,
    protocol = protocol,
    sourceIp = sourceIp,
    method = method,
    cache = cache,
    body = body,
)
internal fun HttpRequest<*>.castPathSpec0(match: PathSpecMap.Match<ServerPathEndpoints>): HttpRequest<PathSpec0> = HttpRequest(
    path = RawPath(path.string, match),
    queryParameters = queryParameters,
    headers = headers,
    domain = domain,
    protocol = protocol,
    sourceIp = sourceIp,
    method = method,
    cache = cache,
    body = body,
)

context(serverRuntime: ServerRuntime)
public suspend fun < Input> Task<Input>.handleWithMetrics(location: PathSpec0, input: Input) {
    return topLevelReportingContext("TASK $location") {
        this.execute(input)
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun ScheduledTask.handleWithMetrics(location: PathSpec0) {
    return topLevelReportingContext("SCHEDULE $location") {
        this.execute()
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun StartupTask.handleWithMetrics(location: PathSpec0) {
    return topLevelReportingContext("STARTUP $location") {
        this.execute()
    }
}



context(serverRuntime: ServerRuntime)
public suspend fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(location: HttpEndpoint<PATH>, request: HttpRequest<PATH>): HttpResponse {
    return topLevelReportingContext(location.toString()) {
        handle(request)
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(location: PATH, serverRuntime: ServerRuntime, request: WebSocketConnectRequest<PATH>): STORAGE {
    return with(serverRuntime) {
        topLevelReportingContext("WEBSOCKET.WILLCONNECT $location") {
            willConnect(request)
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, ) {
    return with(connection) {
        topLevelReportingContext("WEBSOCKET.DIDCONNECT $location") {
            didConnect()
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame) {
    return with(connection) {
        topLevelReportingContext("WEBSOCKET.MESSAGE $location") {
            messageFromClient(frame)
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>) {
    return with(connection) {
        topLevelReportingContext("WEBSOCKET.SUBSCRIPTION $location") {
            messageFromSubscription(topic)
        }
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose) {
    return with(connection) {
        topLevelReportingContext("WEBSOCKET.DISCONNECT $location") {
            disconnect(reason)
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun <T> Task<T>.executeWithMetrics(location: PathSpec0, input: T) {
    return topLevelReportingContext("TASK $location") {
        with(serverRuntime) {
            execute(input)
        }
    }
}
context(serverRuntime: ServerRuntime)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0, ) {
    return topLevelReportingContext("SCHEDULE $location") {
        with(serverRuntime) {
            execute()
        }
    }
}

context(runtime: ServerRuntime)
public suspend inline fun <T> topLevelReportingContext(context: String, crossinline action: suspend () -> T): T =
    topLevelReportingContext(
        context = context,
        metrics = metricsSettings(),
        exceptions = exceptionSettings(),
        action = {
            action()
        }
    )