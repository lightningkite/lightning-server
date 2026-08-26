@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.runtime.phase
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.TypedData
import kotlin.uuid.Uuid

/**
 * Testing extensions for HTTP handlers and WebSocket handlers.
 *
 * These extensions provide a convenient `.test()` method on handlers that:
 * - Creates properly formatted requests with path parameters
 * - Applies interceptors automatically
 * - Returns responses or test WebSocket connections
 * - Uses default values from general settings for domain/protocol
 */

/**
 * Sends a WebSocket subscription message in the test environment.
 */
context(test: TestRunner<*>)
public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(message: WebSocketSubscriptionMessage<PATH, T>) {
    test.sendWebSocketSubscriptionMessage(message)
}

context(test: TestRunner<*>)
public suspend fun <STORAGE> WebSocketHandler<PathSpec0, STORAGE>.test(
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    socketId: Uuid = Uuid.random(),
): TestRunner<*>.TestWebSocket<PathSpec0, STORAGE> {
    val intercepted = test.server.interceptIncomingSocket(this@test)
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, trailingSegments = trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val initiator = Initiator.WebSocket(
        executionId = socketId,
        socketId = socketId,
        path = request.path,
        phase = Initiator.WebSocket.Phase.Connect,
    )
    val storage = with(test.forExecution(initiator)) { intercepted.willConnect(request) }
    return test.TestWebSocket(intercepted, request, initiator, storage).also {
        with(test.forExecution(initiator.phase(Initiator.WebSocket.Phase.Connected))) {
            intercepted.didConnect(it.server)
        }
    }
}

context(test: TestRunner<*>)
public suspend fun <STORAGE, A> WebSocketHandler<PathSpec1<A>, STORAGE>.test(
    path1: A,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    socketId: Uuid = Uuid.random(),
): TestRunner<*>.TestWebSocket<PathSpec1<A>, STORAGE> {
    val intercepted = test.server.interceptIncomingSocket(this@test)
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, path1, trailingSegments = trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val initiator = Initiator.WebSocket(
        executionId = socketId,
        socketId = socketId,
        path = request.path,
        phase = Initiator.WebSocket.Phase.Connect,
    )
    val storage = with(test.forExecution(initiator)) { intercepted.willConnect(request) }
    return test.TestWebSocket(intercepted, request, initiator, storage).also {
        with(test.forExecution(initiator.phase(Initiator.WebSocket.Phase.Connected))) {
            intercepted.didConnect(it.server)
        }
    }
}

context(test: TestRunner<*>)
public suspend fun <STORAGE, A, B> WebSocketHandler<PathSpec2<A, B>, STORAGE>.test(
    path1: A,
    path2: B,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    socketId: Uuid = Uuid.random(),
): TestRunner<*>.TestWebSocket<PathSpec2<A, B>, STORAGE> {
    val intercepted = test.server.interceptIncomingSocket(this@test)
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, path1, path2, trailingSegments = trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val initiator = Initiator.WebSocket(
        executionId = socketId,
        socketId = socketId,
        path = request.path,
        phase = Initiator.WebSocket.Phase.Connect,
    )
    val storage = with(test.forExecution(initiator)) { intercepted.willConnect(request) }
    return test.TestWebSocket(intercepted, request, initiator, storage).also {
        with(test.forExecution(initiator.phase(Initiator.WebSocket.Phase.Connected))) {
            intercepted.didConnect(it.server)
        }
    }
}

context(test: TestRunner<*>)
public suspend fun <STORAGE, A, B, C> WebSocketHandler<PathSpec3<A, B, C>, STORAGE>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    socketId: Uuid = Uuid.random(),
): TestRunner<*>.TestWebSocket<PathSpec3<A, B, C>, STORAGE> {
    val intercepted = test.server.interceptIncomingSocket(this@test)
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, path1, path2, path3),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val initiator = Initiator.WebSocket(
        executionId = socketId,
        socketId = socketId,
        path = request.path,
        phase = Initiator.WebSocket.Phase.Connect,
    )
    val storage = with(test.forExecution(initiator)) { intercepted.willConnect(request) }
    return test.TestWebSocket(this, request, initiator, storage).also {
        with(test.forExecution(initiator.phase(Initiator.WebSocket.Phase.Connected))) {
            intercepted.didConnect(it.server)
        }
    }
}

context(test: TestRunner<*>)
public suspend fun HttpHandler<PathSpec0>.test(
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    requestId: Uuid = generateRequestId(),
    body: TypedData? = null,
): HttpResponse {
    val request: HttpRequest<PathSpec> = HttpRequest(
        RawHttpEndpoint(location.path, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handle(request, requestId)
}

context(test: TestRunner<*>)
public suspend fun <A> HttpHandler<PathSpec1<A>>.test(
    path1: A,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    requestId: Uuid = generateRequestId(),
    body: TypedData? = null,
): HttpResponse {
    val request: HttpRequest<PathSpec> = HttpRequest(
        RawHttpEndpoint(location.path, path1, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handle(request, requestId)
}

context(test: TestRunner<*>)
public suspend fun <A, B> HttpHandler<PathSpec2<A, B>>.test(
    path1: A,
    path2: B,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    requestId: Uuid = generateRequestId(),
    body: TypedData? = null,
): HttpResponse {
    val request: HttpRequest<PathSpec> = HttpRequest(
        RawHttpEndpoint(location.path, path1, path2, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handle(request, requestId)
}

context(test: TestRunner<*>)
public suspend fun <A, B, C> HttpHandler<PathSpec3<A, B, C>>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    requestId: Uuid = generateRequestId(),
    body: TypedData? = null,
): HttpResponse {
    val request: HttpRequest<PathSpec> = HttpRequest(
        RawHttpEndpoint(location.path, path1, path2, path3, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handle(request, requestId)
}

