package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.user
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.serialization.toHttpContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.net.URLDecoder

interface ApiEndpoint<USER, INPUT : Any, OUTPUT> : Documentable, (suspend (HttpRequest) -> HttpResponse) {
    val route: HttpEndpoint
    override val authRequirement: AuthRequirement<USER>
    val inputType: KSerializer<INPUT>
    val outputType: KSerializer<OUTPUT>
    override val summary: String
    override val description: String
    val successCode: HttpStatus
    val errorCases: List<LSError>
    val routeTypes: Map<String, KSerializer<*>>
    val examples: List<ApiExample<INPUT, OUTPUT>>

    override val path: ServerPath
        get() = route.path

    suspend fun invokeAny(user: USER, input: INPUT, routes: Map<String, Any?>): OUTPUT
}

data class ApiExample<INPUT, OUTPUT>(
    val input: INPUT,
    val output: OUTPUT,
    val name: String = "Example",
    val notes: String? = null,
)

data class ApiEndpoint0<USER, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authRequirement: AuthRequirement<USER>,
    override val inputType: KSerializer<INPUT>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) : ApiEndpoint<USER, INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> get() = mapOf()
    override suspend fun invokeAny(user: USER, input: INPUT, routes: Map<String, Any?>) = implementation(user, input)
    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.user(authRequirement)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

data class ApiEndpoint1<USER, PATH : Comparable<PATH>, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authRequirement: AuthRequirement<USER>,
    override val inputType: KSerializer<INPUT>,
    val pathName: String,
    val pathType: KSerializer<PATH>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (user: USER, path: PATH, input: INPUT) -> OUTPUT
) : ApiEndpoint<USER, INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(pathName to pathType)

    @Suppress("UNCHECKED_CAST")
    override suspend fun invokeAny(user: USER, input: INPUT, routes: Map<String, Any?>) =
        implementation(user, routes[pathName] as PATH, input)

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.user(authRequirement)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, it.parts[pathName]!!.parseUrlPartOrBadRequest(pathType), input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

data class ApiEndpoint2<USER, PATH : Comparable<PATH>, PATH2 : Comparable<PATH2>, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authRequirement: AuthRequirement<USER>,
    override val inputType: KSerializer<INPUT>,
    val pathName: String,
    val pathType: KSerializer<PATH>,
    val path2Name: String,
    val path2Type: KSerializer<PATH2>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (user: USER, path: PATH, path2: PATH2, input: INPUT) -> OUTPUT
) : ApiEndpoint<USER, INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(pathName to pathType, path2Name to path2Type)

    @Suppress("UNCHECKED_CAST")
    override suspend fun invokeAny(user: USER, input: INPUT, routes: Map<String, Any?>) =
        implementation(user, routes[pathName] as PATH, routes[path2Name] as PATH2, input)

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.user(authRequirement)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(
            user,
            it.parts[pathName]!!.parseUrlPartOrBadRequest(pathType),
            it.parts[path2Name]!!.parseUrlPartOrBadRequest(path2Type),
            input
        )
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

data class ApiEndpointX<USER, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authRequirement: AuthRequirement<USER>,
    override val inputType: KSerializer<INPUT>,
    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val routeTypes: Map<String, KSerializer<*>>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, Any?>) -> OUTPUT
) : ApiEndpoint<USER, INPUT, OUTPUT> {
    override suspend fun invokeAny(user: USER, input: INPUT, routes: Map<String, Any?>) =
        implementation(user, input, routes)

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.user(authRequirement)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(
            user,
            input,
            it.parts.mapValues { it.value.parseUrlPartOrBadRequestUntyped(routeTypes[it.key]!!) })
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

inline fun <reified T : Comparable<T>> String.parseUrlPartOrBadRequest(): T = parseUrlPartOrBadRequest(
    Serialization.module.serializer()
)

fun <T> String.parseUrlPartOrBadRequest(serializer: KSerializer<T>): T = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

private fun String.parseUrlPartOrBadRequestUntyped(serializer: KSerializer<*>): Any? = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("ID ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    routeTypes: Map<String, KSerializer<*>>,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, input: INPUT, pathSegments: Map<String, Any?>) -> OUTPUT
): ApiEndpointX<USER, INPUT, OUTPUT> = typed(
    authRequirement = AuthRequirement(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    routeTypes = routeTypes,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authRequirement: AuthRequirement<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
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
        examples = examples,
        routeTypes = routeTypes,
        inputType = inputType,
        outputType = outputType,
        authRequirement = authRequirement,
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
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint0<USER, INPUT, OUTPUT> = typed(
    authRequirement = AuthRequirement(),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    successCode = successCode,
    implementation = implementation
)

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authRequirement: AuthRequirement<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint0<USER, INPUT, OUTPUT> {
    val handler = ApiEndpoint0(
        route = this,
        authRequirement = authRequirement,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
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
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, reified ROUTE : Comparable<ROUTE>> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, route: ROUTE, input: INPUT) -> OUTPUT
): ApiEndpoint1<USER, ROUTE, INPUT, OUTPUT> {
    return typed(
        authRequirement = AuthRequirement(),
        inputType = Serialization.module.serializer(),
        outputType = Serialization.module.serializer(),
        pathType = Serialization.module.serializer(),
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = implementation
    )
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, ROUTE : Comparable<ROUTE>> HttpEndpoint.typed(
    authRequirement: AuthRequirement<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    pathType: KSerializer<ROUTE>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, route: ROUTE, input: INPUT) -> OUTPUT
): ApiEndpoint1<USER, ROUTE, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint1(
        route = this,
        authRequirement = authRequirement,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
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
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, reified ROUTE : Comparable<ROUTE>, reified ROUTE2 : Comparable<ROUTE2>> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, route: ROUTE, route2: ROUTE2, input: INPUT) -> OUTPUT
): ApiEndpoint2<USER, ROUTE, ROUTE2, INPUT, OUTPUT> {
    return typed(
        authRequirement = AuthRequirement(),
        inputType = Serialization.module.serializer(),
        outputType = Serialization.module.serializer(),
        pathType = Serialization.module.serializer(),
        path2Type = Serialization.module.serializer(),
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = implementation
    )
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, ROUTE : Comparable<ROUTE>, ROUTE2 : Comparable<ROUTE2>> HttpEndpoint.typed(
    authRequirement: AuthRequirement<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    pathType: KSerializer<ROUTE>,
    path2Type: KSerializer<ROUTE2>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, route: ROUTE, route2: ROUTE2, input: INPUT) -> OUTPUT
): ApiEndpoint2<USER, ROUTE, ROUTE2, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint2(
        route = this,
        authRequirement = authRequirement,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
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


