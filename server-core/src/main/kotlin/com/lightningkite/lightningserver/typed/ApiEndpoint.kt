package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.auth.rawUser
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.net.URLDecoder

interface ApiEndpoint<USER, INPUT : Any, OUTPUT>: Documentable, (suspend (HttpRequest)->HttpResponse) {
    val route: HttpEndpoint
    override val authInfo: AuthInfo<USER>
    val inputType: KSerializer<INPUT>
    val outputType: KSerializer<OUTPUT>
    override val summary: String
    override val description: String
    val successCode: HttpStatus
    val errorCases: List<ErrorCase>
    val routeTypes: Map<String, KSerializer<*>>

    override val path: ServerPath
        get() = route.path

    data class ErrorCase(val status: HttpStatus, val internalCode: Int, val description: String)
}

data class ApiEndpoint0<USER, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authInfo: AuthInfo<USER>,
    override val inputType: KSerializer<INPUT>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<ApiEndpoint.ErrorCase>,
    val implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint<USER, INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> get() = mapOf()
    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = authInfo.checker(it.rawUser())
        @Suppress("UNCHECKED_CAST") val input: INPUT = when(route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if(inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}
data class ApiEndpoint1<USER, PATH: Comparable<PATH>, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authInfo: AuthInfo<USER>,
    override val inputType: KSerializer<INPUT>,
    val pathName: String,
    val pathType: KSerializer<PATH>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<ApiEndpoint.ErrorCase>,
    val implementation: suspend (user: USER, path: PATH, input: INPUT) -> OUTPUT
): ApiEndpoint<USER, INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(pathName to pathType)
    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = authInfo.checker(it.rawUser())
        @Suppress("UNCHECKED_CAST") val input: INPUT = when(route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if(inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, it.parts[pathName]!!.parseUrlPartOrBadRequest(pathType), input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}
data class ApiEndpoint2<USER, PATH: Comparable<PATH>, PATH2: Comparable<PATH2>, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authInfo: AuthInfo<USER>,
    override val inputType: KSerializer<INPUT>,
    val pathName: String,
    val pathType: KSerializer<PATH>,
    val path2Name: String,
    val path2Type: KSerializer<PATH2>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<ApiEndpoint.ErrorCase>,
    val implementation: suspend (user: USER, path: PATH, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint<USER, INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(pathName to pathType, path2Name to path2Type)
    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = authInfo.checker(it.rawUser())
        @Suppress("UNCHECKED_CAST") val input: INPUT = when(route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if(inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, it.parts[pathName]!!.parseUrlPartOrBadRequest(pathType), it.parts[path2Name]!!.parseUrlPartOrBadRequest(path2Type), input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}
data class ApiEndpointX<USER, INPUT: Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authInfo: AuthInfo<USER>,
    override val inputType: KSerializer<INPUT>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<ApiEndpoint.ErrorCase>,
    override val routeTypes: Map<String, KSerializer<*>>,
    val implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, Any?>) -> OUTPUT
): ApiEndpoint<USER, INPUT, OUTPUT> {
    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = authInfo.checker(it.rawUser())
        @Suppress("UNCHECKED_CAST") val input: INPUT = when(route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if(inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, input, it.parts.mapValues { it.value.parseUrlPartOrBadRequestUntyped(routeTypes[it.key]!!) })
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

@kotlinx.serialization.Serializable
data class IdHolder<ID>(val id: ID)
inline fun <reified T: Comparable<T>> String.parseUrlPartOrBadRequest(): T = parseUrlPartOrBadRequest(
    Serialization.module.serializer()
)
fun <T: Comparable<T>> String.parseUrlPartOrBadRequest(serializer: KSerializer<T>): T = try {
    Serialization.properties.decodeFromStringMap(IdHolder.serializer(serializer), mapOf("id" to URLDecoder.decode(this, Charsets.UTF_8))).id
} catch(e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}
private fun String.parseUrlPartOrBadRequestUntyped(serializer: KSerializer<*>): Any? = try {
    Serialization.properties.decodeFromStringMap(IdHolder.serializer(serializer), mapOf("id" to URLDecoder.decode(this, Charsets.UTF_8))).id
} catch(e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    routeTypes: Map<String, KSerializer<*>>,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, Any?>) -> OUTPUT
): ApiEndpointX<USER, INPUT, OUTPUT> = typed(
    authInfo = AuthInfo(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    routeTypes = routeTypes,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authInfo: AuthInfo<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    routeTypes: Map<String, KSerializer<*>> = mapOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, Any?>) -> OUTPUT
): ApiEndpointX<USER, INPUT, OUTPUT> {
    val handler = ApiEndpointX(
        route = this,
        summary = summary,
        description = description,
        successCode = successCode,
        errorCases = errorCases,
        routeTypes = routeTypes,
        inputType = inputType,
        outputType = outputType,
        authInfo = authInfo,
        implementation = implementation
    )
    this.handler(handler)
    return handler
}



/**
 * Builds a typed route.
 */
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint0<USER, INPUT, OUTPUT> = typed(
    authInfo = AuthInfo(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authInfo: AuthInfo<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint0<USER, INPUT, OUTPUT> {
    val handler = ApiEndpoint0(
        route = this,
        authInfo = authInfo,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        successCode = successCode,
        implementation = implementation
    )
    handler(handler)
    return handler
}


/**
 * Builds a typed route.
 */
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, reified ROUTE: Comparable<ROUTE>> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, route: ROUTE, input: INPUT) -> OUTPUT
): ApiEndpoint1<USER, ROUTE, INPUT, OUTPUT> {
    return typed(
        authInfo = AuthInfo(),
        inputType = Serialization.module.serializer(),
        outputType = Serialization.module.serializer(),
        pathType = Serialization.module.serializer(),
        summary = summary,
        description = description,
        errorCases = errorCases,
        successCode = successCode,
        implementation = implementation
    )
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, ROUTE: Comparable<ROUTE>> HttpEndpoint.typed(
    authInfo: AuthInfo<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    pathType: KSerializer<ROUTE>,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, route: ROUTE, input: INPUT) -> OUTPUT
): ApiEndpoint1<USER, ROUTE, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint1(
        route = this,
        authInfo = authInfo,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        successCode = successCode,
        implementation = implementation,
        pathName = segmentNames[0],
        pathType = pathType
    )
    handler(handler)
    return handler
}


/**
 * Builds a typed route.
 */
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, reified ROUTE: Comparable<ROUTE>, reified ROUTE2: Comparable<ROUTE2>> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, route: ROUTE, route2: ROUTE2, input: INPUT) -> OUTPUT
): ApiEndpoint2<USER, ROUTE, ROUTE2, INPUT, OUTPUT> {
    return typed(
        authInfo = AuthInfo(),
        inputType = Serialization.module.serializer(),
        outputType = Serialization.module.serializer(),
        pathType = Serialization.module.serializer(),
        path2Type = Serialization.module.serializer(),
        summary = summary,
        description = description,
        errorCases = errorCases,
        successCode = successCode,
        implementation = implementation
    )
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, ROUTE: Comparable<ROUTE>, ROUTE2: Comparable<ROUTE2>> HttpEndpoint.typed(
    authInfo: AuthInfo<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    pathType: KSerializer<ROUTE>,
    path2Type: KSerializer<ROUTE2>,
    summary: String,
    description: String = summary,
    errorCases: List<ApiEndpoint.ErrorCase>,
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, route: ROUTE, route2: ROUTE2, input: INPUT) -> OUTPUT
): ApiEndpoint2<USER, ROUTE, ROUTE2, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint2(
        route = this,
        authInfo = authInfo,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        successCode = successCode,
        implementation = implementation,
        pathName = segmentNames[0],
        pathType = pathType,
        path2Name = segmentNames[1],
        path2Type = path2Type
    )
    handler(handler)
    return handler
}


