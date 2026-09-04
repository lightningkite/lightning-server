package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.assert
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
/**
 * Checks [presented] against this endpoint's own [auth] requirement, the way a real request does.
 *
 * These helpers used to hand the handler an [Access] built directly from whatever authentication the
 * test supplied, which meant the endpoint's requirement was never consulted. Any test asserting "this
 * caller is refused" therefore passed whether or not the endpoint required anything at all — it would
 * have passed against an endpoint with no requirement, which is the opposite of what it read as.
 *
 * The real path does this inside [access]; going through the same check here is what makes an
 * authorization test in a typed harness mean something. A rejected caller now gets the
 * [com.lightningkite.lightningserver.ForbiddenException] the endpoint would really have produced.
 */
context(test: TestRunner<*>)
private suspend fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT>
    ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.assertAuth(
    presented: Authentication<*>?,
): Authentication<USER & Any>? = with(test) { auth.assert(presented) }

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT> ApiHttpHandler<PathSpec0, USER, INPUT, OUTPUT>.test(
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
    )
}

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A> ApiHttpHandler<PathSpec1<A>, USER, INPUT, OUTPUT>.test(
    path1: A,
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, path1, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
    )
}

context(test: TestRunner<*>)
public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A, B> ApiHttpHandler<PathSpec2<A, B>, USER, INPUT, OUTPUT>.test(
    path1: A,
    path2: B,
    auth: Authentication<USER>,
    input: INPUT,
): OUTPUT {
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, path1, path2, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
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
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, path1, path2, path3, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT> ApiHttpHandler<PathSpec0, USER?, INPUT, OUTPUT>.test(
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A> ApiHttpHandler<PathSpec1<A>, USER?, INPUT, OUTPUT>.test(
    path1: A,
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, path1, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
    )
}

@JvmName("testNullableAuth")
context(test: TestRunner<*>) public suspend fun <USER : HasId<*>, INPUT, OUTPUT, A, B> ApiHttpHandler<PathSpec2<A, B>, USER?, INPUT, OUTPUT>.test(
    path1: A,
    path2: B,
    auth: Authentication<USER>?,
    input: INPUT,
): OUTPUT {
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, path1, path2, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
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
    return handle(
        access = HttpAccess(
            HttpRequest(
                RawHttpEndpoint(location.path, path1, path2, path3, method = location.method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders(),
                domain = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
                protocol = generalSettings().publicUrl.substringBefore("://"),
                sourceIp = "localhost",
                body = TypedData.text(
                    test.externalSerialization.json.encodeToString(inputType, input),
                    MediaType.Application.Json
                ),
            ),
            assertAuth(auth),
        ),
        input
    )
}