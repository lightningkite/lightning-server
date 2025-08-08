package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.topLevelReportingContext

context(serverRuntime: ServerRuntime)
public suspend fun <PATH : PathSpec> Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>>.handleWithMetrics(request: HttpRequest<PATH>): HttpResponse {
    return topLevelReportingContext(this.location.toString(), serverRuntime) {
        item.handle(serverRuntime, request)
    }
}
context(serverRuntime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> Locationed<PATH, WebSocketHandler<PATH, STORAGE>>.willConnectWithMetrics(request: WebSocketConnectRequest<PATH>): STORAGE {
    return topLevelReportingContext(this.location.toString(), serverRuntime) {
        item.willConnect(serverRuntime, request)
    }
}
context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE> Locationed<PATH, WebSocketHandler<PATH, STORAGE>>.didConnectWithMetrics() {
    return topLevelReportingContext(this.location.toString(), connection) {
        item.didConnect(connection)
    }
}
context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE> Locationed<PATH, WebSocketHandler<PATH, STORAGE>>.messageFromClientWithMetrics(frame: WebSocketFrame) {
    return topLevelReportingContext(this.location.toString(), connection) {
        item.messageFromClient(connection, frame)
    }
}
context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE> Locationed<PATH, WebSocketHandler<PATH, STORAGE>>.messageFromSubscriptionWithMetrics(topic: WebSocketSubscriptionMessage<*, *>) {
    return topLevelReportingContext(this.location.toString(), connection) {
        item.messageFromSubscription(connection, topic)
    }
}
context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE> Locationed<PATH, WebSocketHandler<PATH, STORAGE>>.disconnectWithMetrics(reason: WebSocketClose) {
    return topLevelReportingContext(this.location.toString(), connection) {
        item.disconnect(connection, reason)
    }
}

context(serverRuntime: ServerRuntime)
public suspend fun <T> Locationed<PathSpec0, Task<T>>.executeWithMetrics(input: T) {
    return topLevelReportingContext(this.location.toString(), serverRuntime) {
        with(serverRuntime) {
            item.execute(input)
        }
    }
}
context(serverRuntime: ServerRuntime)
public suspend fun Locationed<PathSpec0, ScheduledTask>.executeWithMetrics() {
    return topLevelReportingContext(this.location.toString(), serverRuntime) {
        with(serverRuntime) {
            item.execute()
        }
    }
}