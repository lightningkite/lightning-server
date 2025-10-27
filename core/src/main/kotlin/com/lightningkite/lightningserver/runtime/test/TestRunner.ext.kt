package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebsocketPath
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.TypedData


context(test: TestRunner<*>)
public suspend fun <PATH: PathSpec, T> sendWebSocketSubscriptionMessage(message: WebSocketSubscriptionMessage<PATH, T>) {
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
): TestRunner<*>.TestWebSocket<PathSpec0, STORAGE> {
    val intercepted = test.server.compiledWebsocketInterceptors.intercept(this@test)
    val request = WebSocketConnectRequest(
        RawWebsocketPath(location, trailingSegments = trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = intercepted.willConnect(request)
    return test.TestWebSocket(intercepted, request, storage).also {
        with(it.server) { intercepted.didConnect() }
    }
}
context(test: TestRunner<*>) public suspend fun <STORAGE, A> WebSocketHandler<PathSpec1<A>, STORAGE>.test(
    path1: A,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec1<A>, STORAGE> {
    val intercepted = test.server.compiledWebsocketInterceptors.intercept(this@test)
    val request = WebSocketConnectRequest(
        RawWebsocketPath(location, path1, trailingSegments = trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = intercepted.willConnect(request)
    return test.TestWebSocket(intercepted, request, storage).also {
        with(it.server) { intercepted.didConnect() }
    }
}
context(test: TestRunner<*>) public suspend fun <STORAGE, A, B> WebSocketHandler<PathSpec2<A, B>, STORAGE>.test(
    path1: A,
    path2: B,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec2<A, B>, STORAGE> {
    val intercepted = test.server.compiledWebsocketInterceptors.intercept(this@test)
    val request = WebSocketConnectRequest(
        RawWebsocketPath(location, path1, path2, trailingSegments = trailingWildcard),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = with(test) { intercepted.willConnect(request) }
    return test.TestWebSocket(intercepted, request, storage).also {
        with(it.server) { intercepted.didConnect() }
    }
}
context(test: TestRunner<*>) public suspend fun <STORAGE, A, B, C> WebSocketHandler<PathSpec3<A, B, C>, STORAGE>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec3<A, B, C>, STORAGE> {
    val intercepted = test.server.compiledWebsocketInterceptors.intercept(this@test)
    val request = WebSocketConnectRequest(
        RawWebsocketPath(location, path1, path2, path3),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = with(test) { intercepted.willConnect(request) }
    return test.TestWebSocket(this, request, storage).also {
        with(it.server) { intercepted.didConnect() }
    }
}
context(test: TestRunner<*>) public suspend fun HttpHandler<PathSpec0>.test(
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return test.handle(
        HttpRequest(
            RawHttpEndpoint(location.path, location.method, trailingWildcard),
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

context(test: TestRunner<*>) public suspend fun <A> HttpHandler<PathSpec1<A>>.test(
    path1: A,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return test.handle(
        HttpRequest(
            RawHttpEndpoint(location.path, path1, location.method, trailingWildcard),
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}
context(test: TestRunner<*>) public suspend fun <A, B> HttpHandler<PathSpec2<A, B>>.test(
    path1: A,
    path2: B,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return test.handle(
        HttpRequest(
            RawHttpEndpoint(location.path, path1, path2, location.method, trailingWildcard),
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}
context(test: TestRunner<*>) public suspend fun <A, B, C> HttpHandler<PathSpec3<A, B, C>>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: QueryParameters = QueryParameters.EMPTY,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    trailingWildcard: PathSegments? = null,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return test.handle(
        HttpRequest(
            RawHttpEndpoint(location.path, path1, path2, path3, location.method, trailingWildcard),
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

