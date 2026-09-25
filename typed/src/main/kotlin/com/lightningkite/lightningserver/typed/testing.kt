package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.HasId

//
//context(test: TestRunner<*>)
//public suspend fun <STORAGE> WebSocketHandler<PathSpec0, STORAGE>.test(
//    queryParameters: QueryParameters = listOf(),
//    headers: HttpHeaders = HttpHeaders.EMPTY,
//    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
//    protocol: String = generalSettings().publicUrl.substringBefore("://"),
//    sourceIp: String = "local",
//): TestRunner<*>.TestWebSocket<PathSpec0, STORAGE> {
//    val request = WebSocketConnectRequest(
//        RawPath(location),
//        queryParameters = queryParameters,
//        headers = headers,
//        domain = domain,
//        protocol = protocol,
//        sourceIp = sourceIp,
//    )
//    val storage = willConnect(request)
//    return test.TestWebSocket(this, request, storage).also {
//        with(it.server) { didConnect() }
//    }
//}
//context(test: TestRunner<*>) public suspend fun <STORAGE, A> WebSocketHandler<PathSpec1<A>, STORAGE>.test(
//    path1: A,
//    queryParameters: QueryParameters = listOf(),
//    headers: HttpHeaders = HttpHeaders.EMPTY,
//    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
//    protocol: String = generalSettings().publicUrl.substringBefore("://"),
//    sourceIp: String = "local",
//): TestRunner<*>.TestWebSocket<PathSpec1<A>, STORAGE> {
//    val request = WebSocketConnectRequest(
//        RawPath(location, path1),
//        queryParameters = queryParameters,
//        headers = headers,
//        domain = domain,
//        protocol = protocol,
//        sourceIp = sourceIp,
//    )
//    val storage = with(test) { willConnect(request) }
//    return test.TestWebSocket(this, request, storage).also {
//        with(it.server) { didConnect() }
//    }
//}
//context(test: TestRunner<*>) public suspend fun <STORAGE, A, B> WebSocketHandler<PathSpec2<A, B>, STORAGE>.test(
//    path1: A,
//    path2: B,
//    queryParameters: QueryParameters = listOf(),
//    headers: HttpHeaders = HttpHeaders.EMPTY,
//    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
//    protocol: String = generalSettings().publicUrl.substringBefore("://"),
//    sourceIp: String = "local",
//): TestRunner<*>.TestWebSocket<PathSpec2<A, B>, STORAGE> {
//    val request = WebSocketConnectRequest(
//        RawPath(location, path1, path2),
//        queryParameters = queryParameters,
//        headers = headers,
//        domain = domain,
//        protocol = protocol,
//        sourceIp = sourceIp,
//    )
//    val storage = with(test) { willConnect(request) }
//    return test.TestWebSocket(this, request, storage).also {
//        with(it.server) { didConnect() }
//    }
//}
//context(test: TestRunner<*>) public suspend fun <STORAGE, A, B, C> WebSocketHandler<PathSpec3<A, B, C>, STORAGE>.test(
//    path1: A,
//    path2: B,
//    path3: C,
//    queryParameters: QueryParameters = listOf(),
//    headers: HttpHeaders = HttpHeaders.EMPTY,
//    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
//    protocol: String = generalSettings().publicUrl.substringBefore("://"),
//    sourceIp: String = "local",
//): TestRunner<*>.TestWebSocket<PathSpec3<A, B, C>, STORAGE> {
//    val request = WebSocketConnectRequest(
//        RawPath(location, path1, path2, path3),
//        queryParameters = queryParameters,
//        headers = headers,
//        domain = domain,
//        protocol = protocol,
//        sourceIp = sourceIp,
//    )
//    val storage = with(test) { willConnect(request) }
//    return test.TestWebSocket(this, request, storage).also {
//        with(it.server) { didConnect() }
//    }
//}
// Runs the endpoint as a direct call from the test, through validation, its own auth requirement, the
// HTTP interceptors and typed output observation. [presented] stands in for whatever the request's
// credentials would have resolved to, so the requirement is checked against it as it would be for a
// real request.
context(test: TestRunner<*>)
private suspend fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.testWith(
    path: RawHttpEndpoint<PATH>,
    presented: Authentication<*>?,
    input: INPUT,
): OUTPUT {
    val request = HttpRequest(
        path,
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders(),
        domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
        protocol = generalSettings().publicUrl.substringBefore("://"),
        sourceIp = "localhost",
        cache = SerializableCache().apply { set(Authentication.CacheKey, presented) },
        body = TypedData.text(
            test.externalSerialization.json.encodeToString(inputType, input),
            MediaType.Application.Json
        ),
    )
    return test.execute { handleWithMetrics(request, input) }
}

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT> ApiHttpHandler<PathSpec0, USER, INPUT, OUTPUT>.test(
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, method = location.method),
        auth,
        input,
    )
}

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A> ApiHttpHandler<PathSpec1<A>, USER, INPUT, OUTPUT>.test(
    path1: A,
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, path1, method = location.method),
        auth,
        input,
    )
}

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A, B> ApiHttpHandler<PathSpec2<A, B>, USER, INPUT, OUTPUT>.test(
    path1: A,
    path2: B,
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, path1, path2, method = location.method),
        auth,
        input,
    )
}

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A, B, C> ApiHttpHandler<PathSpec3<A, B, C>, USER, INPUT, OUTPUT>.test(
    path1: A,
    path2: B,
    path3: C,
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, path1, path2, path3, method = location.method),
        auth,
        input,
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT> ApiHttpHandler<PathSpec0, USER?, INPUT, OUTPUT>.test(
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, method = location.method),
        auth,
        input,
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A> ApiHttpHandler<PathSpec1<A>, USER?, INPUT, OUTPUT>.test(
    path1: A,
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, path1, method = location.method),
        auth,
        input,
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A, B> ApiHttpHandler<PathSpec2<A, B>, USER?, INPUT, OUTPUT>.test(
    path1: A,
    path2: B,
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, path1, path2, method = location.method),
        auth,
        input,
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A, B, C> ApiHttpHandler<PathSpec3<A, B, C>, USER?, INPUT, OUTPUT>.test(
    path1: A,
    path2: B,
    path3: C,
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return testWith(
        RawHttpEndpoint(location.path, path1, path2, path3, method = location.method),
        auth,
        input,
    )
}