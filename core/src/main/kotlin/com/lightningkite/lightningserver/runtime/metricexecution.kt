package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.topLevelReportingContext

context(serverRuntime: ServerRuntime)
public suspend fun <PATH : PathSpec> HttpHandler<PATH>.handleWithMetrics(location: HttpEndpoint<PATH>, request: HttpRequest<PATH>): HttpResponse {
    return topLevelReportingContext(location.toString(), serverRuntime.metrics) {
        handle(request)
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(location: PATH, serverRuntime: ServerRuntime, request: WebSocketConnectRequest<PATH>): STORAGE {
    return topLevelReportingContext("WEBSOCKET.WILLCONNECT $location", serverRuntime.metrics) {
        willConnect(serverRuntime, request)
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, ) {
    return topLevelReportingContext("WEBSOCKET.DIDCONNECT $location", connection.metrics) {
        didConnect(connection)
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame) {
    return topLevelReportingContext("WEBSOCKET.MESSAGE $location", connection.metrics) {
        messageFromClient(connection, frame)
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>) {
    return topLevelReportingContext("WEBSOCKET.SUBSCRIPTION $location", connection.metrics) {
        messageFromSubscription(connection, topic)
    }
}
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(location: PATH, connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose) {
    return topLevelReportingContext("WEBSOCKET.DISCONNECT $location", connection.metrics) {
        disconnect(connection, reason)
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun <T> Task<T>.executeWithMetrics(location: PathSpec0, input: T) {
    return topLevelReportingContext("TASK $location", serverRuntime.metrics) {
        with(serverRuntime) {
            execute(input)
        }
    }
}
context(serverRuntime: ServerRuntime)
public suspend fun ScheduledTask.executeWithMetrics(location: PathSpec0, ) {
    return topLevelReportingContext("SCHEDULE $location", serverRuntime.metrics) {
        with(serverRuntime) {
            execute()
        }
    }
}