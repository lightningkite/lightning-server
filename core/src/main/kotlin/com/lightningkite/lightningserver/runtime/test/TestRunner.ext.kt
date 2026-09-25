@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.didConnectWithMetrics
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.willConnectWithMetrics
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.TypedData

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
 * Runs [action] as the test's own work, with a [ServerRuntime] in context. See [TestRunner.execute].
 */
context(test: TestRunner<*>)
public suspend inline fun <T> execute(crossinline action: suspend context(ServerRuntime) () -> T): T = test.execute(action)

// Connects as an engine would: willConnect and didConnect each run as their own phase, through the
// server's socket interceptors.
context(test: TestRunner<*>)
private suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.connect(
    request: WebSocketConnectRequest<PATH>,
): TestRunner<*>.TestWebSocket<PATH, STORAGE> {
    val intercepted = test.server.interceptIncomingSocket(this)
    val storage = intercepted.willConnectWithMetrics(request)
    return test.TestWebSocket(intercepted, request, storage).also { intercepted.didConnectWithMetrics(it.server) }
}

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
    socketId: Execution.ID = Execution.ID.generate(),
): TestRunner<*>.TestWebSocket<PathSpec0, STORAGE> {
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, trailingSegments = trailingWildcard),
        socketId = socketId,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    return connect(request)
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
    socketId: Execution.ID = Execution.ID.generate(),
): TestRunner<*>.TestWebSocket<PathSpec1<A>, STORAGE> {
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, path1, trailingSegments = trailingWildcard),
        socketId = socketId,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    return connect(request)
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
    socketId: Execution.ID = Execution.ID.generate(),
): TestRunner<*>.TestWebSocket<PathSpec2<A, B>, STORAGE> {
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, path1, path2, trailingSegments = trailingWildcard),
        socketId = socketId,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    return connect(request)
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
    socketId: Execution.ID = Execution.ID.generate(),
): TestRunner<*>.TestWebSocket<PathSpec3<A, B, C>, STORAGE> {
    val request = WebSocketConnectRequest(
        RawWebSocketPath(location, path1, path2, path3, trailingSegments = trailingWildcard),
        socketId = socketId,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    return connect(request)
}

context(test: TestRunner<*>)
public suspend fun HttpHandler<PathSpec0>.test(
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    requestId: Execution.ID = Execution.ID.generate(),
    body: TypedData? = null,
): HttpResponse {
    val request = HttpRequest(
        RawHttpEndpoint(location.path, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handleRoot(request, requestId)
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
    requestId: Execution.ID = Execution.ID.generate(),
    body: TypedData? = null,
): HttpResponse {
    val request = HttpRequest(
        RawHttpEndpoint(location.path, path1, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handleRoot(request, requestId)
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
    requestId: Execution.ID = Execution.ID.generate(),
    body: TypedData? = null,
): HttpResponse {
    val request = HttpRequest(
        RawHttpEndpoint(location.path, path1, path2, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handleRoot(request, requestId)
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
    requestId: Execution.ID = Execution.ID.generate(),
    body: TypedData? = null,
): HttpResponse {
    val request = HttpRequest(
        RawHttpEndpoint(location.path, path1, path2, path3, location.method, trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        body = body,
    )
    return test.handleRoot(request, requestId)
}

