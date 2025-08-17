package com.lightningkite.lightningserver.runtime.test

import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.TypedData


context(test: TestRunner<*>)
public suspend fun <PATH: PathSpec, T> sendWebSocketSubscriptionMessage(message: WebSocketSubscriptionMessage<PATH, T>) {
    test.sendWebSocketSubscriptionMessage(message)
}

context(test: TestRunner<*>)
public suspend fun <STORAGE> Locationed<PathSpec0, WebSocketHandler<PathSpec0, STORAGE>>.test(
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec0, STORAGE> {
    val request = WebSocketConnectRequest(
        RawPath(this.location),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = item.willConnect(
        test, request
    )
    return test.TestWebSocket(item, request, storage).also {
        item.didConnect(it.server)
    }
}
context(test: TestRunner<*>) public suspend fun <STORAGE, A> Locationed<PathSpec1<A>, WebSocketHandler<PathSpec1<A>, STORAGE>>.test(
    path1: A,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec1<A>, STORAGE> {
    val request = WebSocketConnectRequest(
        RawPath(this.location, path1),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = item.willConnect(
        test, request
    )
    return test.TestWebSocket(item, request, storage)
}
context(test: TestRunner<*>) public suspend fun <STORAGE, A, B> Locationed<PathSpec2<A, B>, WebSocketHandler<PathSpec2<A, B>, STORAGE>>.test(
    path1: A,
    path2: B,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec2<A, B>, STORAGE> {
    val request = WebSocketConnectRequest(
        RawPath(this.location, path1, path2),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = item.willConnect(
        test, request
    )
    return test.TestWebSocket(item, request, storage)
}
context(test: TestRunner<*>) public suspend fun <STORAGE, A, B, C> Locationed<PathSpec3<A, B, C>, WebSocketHandler<PathSpec3<A, B, C>, STORAGE>>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
): TestRunner<*>.TestWebSocket<PathSpec3<A, B, C>, STORAGE> {
    val request = WebSocketConnectRequest(
        RawPath(this.location, path1, path2, path3),
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
    )
    val storage = item.willConnect(
        test, request
    )
    return test.TestWebSocket(item, request, storage)
}
context(test: TestRunner<*>) public suspend fun Locationed<HttpEndpoint<PathSpec0>, HttpHandler<PathSpec0>>.test(
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            RawPath(this.location.path),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

context(test: TestRunner<*>) public suspend fun <A> Locationed<HttpEndpoint<PathSpec1<A>>, HttpHandler<PathSpec1<A>>>.test(
    path1: A,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            RawPath(this.location.path, path1),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}
context(test: TestRunner<*>) public suspend fun <A, B> Locationed<HttpEndpoint<PathSpec2<A, B>>, HttpHandler<PathSpec2<A, B>>>.test(
    path1: A,
    path2: B,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            RawPath(this.location.path, path1, path2),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}
context(test: TestRunner<*>) public suspend fun <A, B, C> Locationed<HttpEndpoint<PathSpec3<A, B, C>>, HttpHandler<PathSpec3<A, B, C>>>.test(
    path1: A,
    path2: B,
    path3: C,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "local",
    body: TypedData? = null,
): HttpResponse {
    return this.item.handle(
        test, HttpRequest(
            RawPath(this.location.path, path1, path2, path3),
            method = this.location.method,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            body = body,
        )
    )
}

