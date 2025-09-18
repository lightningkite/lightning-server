package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.intercept
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage

public suspend fun ServerRuntime.handle(request: HttpRequest<PathSpec>): HttpResponse {
    return try {
        @Suppress("UNCHECKED_CAST") val rawHandler = request.path.match.value as HttpHandler<PathSpec>
        val handler = server.httpInterceptors.intercept(rawHandler)
        topLevelReportingContext(request.path.toString()) { handler.handle(request) }
    } catch (e: Exception) {
        try {
            topLevelReportingContext("exceptionHandler") {
                this.server.exceptionHandler.handle(request, e)
            }
        } catch (e: Exception) {
            HttpResponse(status = HttpStatus.InternalServerError)
        }
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun < Input> Task<Input>.handleWithMetrics(location: PathSpec0, input: Input) {
    return topLevelReportingContext("TASK $location") {
        this.executeInline(input)
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
            executeInline(input)
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
public suspend inline fun <T> topLevelReportingContext(context: String, crossinline action: suspend () -> T): T {
    return action()
    //TODO: Open telemetry
}