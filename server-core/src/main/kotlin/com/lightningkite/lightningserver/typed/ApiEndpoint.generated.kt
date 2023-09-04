package com.lightningkite.lightningserver.typed

import java.time.Duration
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
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




data class ApiEndpoint0<INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authOptions: AuthOptions,
    override val inputType: KSerializer<INPUT>,

    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (auth: RequestAuth<*>?, input: INPUT) -> OUTPUT
) : ApiEndpoint<INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(

    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun invokeAny(auth: RequestAuth<*>, input: INPUT, routes: Map<String, Any?>) =
        implementation(auth, input)

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.authChecked(authOptions)
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

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <INPUT : Any, OUTPUT, > HttpEndpoint.typedAuthAbstracted(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<*>?, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint0(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, input ->
            implementation(auth, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge),
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
@JvmName("typedDirect")
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint0(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth?.get() as USER, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
@JvmName("typedRequired")
inline fun <reified USER: HasId<*>, reified INPUT : Any, reified OUTPUT, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (auth: RequestAuth<USER>, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge),
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
@JvmName("typedRequired")
fun <USER: HasId<*>, INPUT : Any, OUTPUT, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<USER>, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint0(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth as RequestAuth<USER>, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
@JvmName("typedOptional")
inline fun <reified USER: HasId<*>, reified INPUT : Any, reified OUTPUT, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (auth: RequestAuth<USER>?, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge) + setOf(null),
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
@JvmName("typedOptional")
fun <USER: HasId<*>, INPUT : Any, OUTPUT, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<USER>?, input: INPUT) -> OUTPUT
): ApiEndpoint0<INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint0(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth as RequestAuth<USER>?, input)
        }
    )
    handler(handler)
    return handler
}

data class ApiEndpoint1<PATH1 : Comparable<PATH1>, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authOptions: AuthOptions,
    override val inputType: KSerializer<INPUT>,

    val path1Name: String,
    val path1Type: KSerializer<PATH1>,

    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (auth: RequestAuth<*>?, path1: PATH1, input: INPUT) -> OUTPUT
) : ApiEndpoint<INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(

        path1Name to path1Type,

        )

    @Suppress("UNCHECKED_CAST")
    override suspend fun invokeAny(auth: RequestAuth<*>, input: INPUT, routes: Map<String, Any?>) =
        implementation(auth, routes[path1Name] as PATH1, input)

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.authChecked(authOptions)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, it.parts[path1Name]!!.parseUrlPartOrBadRequest(path1Type), input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, > HttpEndpoint.typedAuthAbstracted(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<*>?, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint1(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, input ->
            implementation(auth, path1, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, reified PATH1 : Comparable<PATH1>, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    path1Type = Serialization.module.serializer(),

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
@JvmName("typedDirect")
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint1(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth?.get() as USER, path1, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
@JvmName("typedRequired")
inline fun <reified USER: HasId<*>, reified INPUT : Any, reified OUTPUT, reified PATH1 : Comparable<PATH1>, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (auth: RequestAuth<USER>, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    path1Type = Serialization.module.serializer(),

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
@JvmName("typedRequired")
fun <USER: HasId<*>, INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<USER>, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint1(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth as RequestAuth<USER>, path1, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
@JvmName("typedOptional")
inline fun <reified USER: HasId<*>, reified INPUT : Any, reified OUTPUT, reified PATH1 : Comparable<PATH1>, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (auth: RequestAuth<USER>?, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge) + setOf(null),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    path1Type = Serialization.module.serializer(),

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
@JvmName("typedOptional")
fun <USER: HasId<*>, INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<USER>?, path1: PATH1, input: INPUT) -> OUTPUT
): ApiEndpoint1<PATH1, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint1(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth as RequestAuth<USER>?, path1, input)
        }
    )
    handler(handler)
    return handler
}

data class ApiEndpoint2<PATH1 : Comparable<PATH1>, PATH2 : Comparable<PATH2>, INPUT : Any, OUTPUT>(
    override val route: HttpEndpoint,
    override val authOptions: AuthOptions,
    override val inputType: KSerializer<INPUT>,

    val path1Name: String,
    val path1Type: KSerializer<PATH1>,


    val path2Name: String,
    val path2Type: KSerializer<PATH2>,

    override val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String = summary,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError>,
    override val examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    val implementation: suspend (auth: RequestAuth<*>?, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
) : ApiEndpoint<INPUT, OUTPUT> {
    override val routeTypes: Map<String, KSerializer<*>> = mapOf(

        path1Name to path1Type,


        path2Name to path2Type,

        )

    @Suppress("UNCHECKED_CAST")
    override suspend fun invokeAny(auth: RequestAuth<*>, input: INPUT, routes: Map<String, Any?>) =
        implementation(auth, routes[path1Name] as PATH1, routes[path2Name] as PATH2, input)

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val user = it.authChecked(authOptions)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body!!.parse(inputType)
        }
        val result = implementation(user, it.parts[path1Name]!!.parseUrlPartOrBadRequest(path1Type), it.parts[path2Name]!!.parseUrlPartOrBadRequest(path2Type), input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
fun <INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, PATH2 : Comparable<PATH2>, > HttpEndpoint.typedAuthAbstracted(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,


    path2Type: KSerializer<PATH2>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<*>?, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint2(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,


        path2Name = segmentNames[1],
        path2Type = path2Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, path2, input ->
            implementation(auth, path1, path2, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
inline fun <reified USER, reified INPUT : Any, reified OUTPUT, reified PATH1 : Comparable<PATH1>, reified PATH2 : Comparable<PATH2>, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (user: USER, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    path1Type = Serialization.module.serializer(),


    path2Type = Serialization.module.serializer(),

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
@JvmName("typedDirect")
@LightningServerDsl
fun <USER, INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, PATH2 : Comparable<PATH2>, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,


    path2Type: KSerializer<PATH2>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint2(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,


        path2Name = segmentNames[1],
        path2Type = path2Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, path2, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth?.get() as USER, path1, path2, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
@JvmName("typedRequired")
inline fun <reified USER: HasId<*>, reified INPUT : Any, reified OUTPUT, reified PATH1 : Comparable<PATH1>, reified PATH2 : Comparable<PATH2>, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (auth: RequestAuth<USER>, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    path1Type = Serialization.module.serializer(),


    path2Type = Serialization.module.serializer(),

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
@JvmName("typedRequired")
fun <USER: HasId<*>, INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, PATH2 : Comparable<PATH2>, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,


    path2Type: KSerializer<PATH2>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<USER>, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint2(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,


        path2Name = segmentNames[1],
        path2Type = path2Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, path2, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth as RequestAuth<USER>, path1, path2, input)
        }
    )
    handler(handler)
    return handler
}

/**
 * Builds a typed route.
 */
@LightningServerDsl
@JvmName("typedOptional")
inline fun <reified USER: HasId<*>, reified INPUT : Any, reified OUTPUT, reified PATH1 : Comparable<PATH1>, reified PATH2 : Comparable<PATH2>, > HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend (auth: RequestAuth<USER>?, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> = typed(
    authOptions = authOptions<USER>(scopes = scopes, maxAge = maxAge) + setOf(null),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    path1Type = Serialization.module.serializer(),


    path2Type = Serialization.module.serializer(),

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
@JvmName("typedOptional")
fun <USER: HasId<*>, INPUT : Any, OUTPUT, PATH1 : Comparable<PATH1>, PATH2 : Comparable<PATH2>, > HttpEndpoint.typed(
    authOptions: AuthOptions,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    path1Type: KSerializer<PATH1>,


    path2Type: KSerializer<PATH2>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (auth: RequestAuth<USER>?, path1: PATH1, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint2<PATH1, PATH2, INPUT, OUTPUT> {
    val segmentNames = this.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().map { it.name }
    val handler = ApiEndpoint2(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,

        path1Name = segmentNames[0],
        path1Type = path1Type,


        path2Name = segmentNames[1],
        path2Type = path2Type,

        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { auth, path1, path2, input ->
            @Suppress("UNCHECKED_CAST")
            implementation(auth as RequestAuth<USER>?, path1, path2, input)
        }
    )
    handler(handler)
    return handler
}