package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.contextualSerializerIfHandled
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
import java.net.URLDecoder

data class ApiEndpoint<USER: HasId<*>?, PATH: TypedServerPath, INPUT, OUTPUT>(
    val route: TypedHttpEndpoint<PATH>,
    override val authOptions: AuthOptions<USER>,
    val inputType: KSerializer<INPUT>,
    val outputType: KSerializer<OUTPUT>,
    override val summary: String,
    override val description: String,
    val successCode: HttpStatus,
    val errorCases: List<LSError>,
    val examples: List<ApiExample<INPUT, OUTPUT>>,
    override val belongsToInterface: Documentable.InterfaceInfo? = null,
    val implementation: suspend AuthAndPathParts<USER, PATH>.(INPUT)->OUTPUT,
) : Documentable, (suspend (HttpRequest) -> HttpResponse) {
    override val path: TypedServerPath
        get() = route.path
    private val wildcards = route.path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()

    suspend fun authAndPathParts(auth: RequestAuth<USER & Any>?, request: HttpRequest) = AuthAndPathParts<USER, PATH>(
        authOrNull = auth,
        rawRequest = request,
        parts = route.path.serializers.mapIndexed { idx, ser ->
            val name = wildcards.get(idx).name
            val str = request.parts[name] ?: throw BadRequestException("Route segment $name not found")
            str.parseUrlPartOrBadRequest(ser)
        }.toTypedArray()
    ).also {
        authOptions.assert(it.authOrNull)
    }

    override suspend fun invoke(it: HttpRequest): HttpResponse {
        val auth = it.authChecked<USER>(authOptions)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (route.method) {
            HttpMethod.GET, HttpMethod.HEAD -> it.queryParameters(inputType)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else it.body?.parse(inputType) ?: throw BadRequestException("No request body provided")
        }
        Serialization.validateOrThrow(inputType, input)
        val result = authAndPathParts(auth, it).implementation(input)
        return HttpResponse(
            body = result.toHttpContent(it.headers.accept, outputType),
            status = successCode
        )
    }
}

data class ApiExample<INPUT, OUTPUT>(
    val input: INPUT,
    val output: OUTPUT,
    val name: String = "Example",
    val notes: String? = null,
)

private fun <T> String.parseUrlPartOrBadRequest(serializer: KSerializer<T>): T = try {
    Serialization.fromString(URLDecoder.decode(this, Charsets.UTF_8), serializer)
} catch (e: Exception) {
    throw BadRequestException("Path part ${this} could not be parsed as a ${serializer.descriptor.serialName}.")
}

@LightningServerDsl
fun <USER: HasId<*>?, PATH: TypedServerPath, INPUT, OUTPUT> TypedHttpEndpoint<PATH>.api(
    authOptions: AuthOptions<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    belongsToInterface: Documentable.InterfaceInfo? = null,
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend AuthAndPathParts<USER, PATH>.(INPUT)->OUTPUT
): ApiEndpoint<USER, PATH, INPUT, OUTPUT> {
    val api = ApiEndpoint<USER, PATH, INPUT, OUTPUT>(
        route = this,
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        belongsToInterface = belongsToInterface,
        successCode = successCode,
        implementation = implementation,
    )
    endpoint.handler(api)
    return api
}

@LightningServerDsl
inline fun <USER: HasId<*>?, PATH: TypedServerPath, reified INPUT, reified OUTPUT> TypedHttpEndpoint<PATH>.api(
    summary: String,
    description: String = summary,
    authOptions: AuthOptions<USER>,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    belongsToInterface: Documentable.InterfaceInfo? = null,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend AuthAndPathParts<USER, PATH>.(INPUT)->OUTPUT
): ApiEndpoint<USER, PATH, INPUT, OUTPUT> {
    val api = ApiEndpoint<USER, PATH, INPUT, OUTPUT>(
        route = this,
        authOptions = authOptions,
        inputType = Serialization.module.contextualSerializerIfHandled<INPUT>(),
        outputType = Serialization.module.contextualSerializerIfHandled<OUTPUT>(),
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        belongsToInterface = belongsToInterface,
        successCode = successCode,
        implementation = implementation,
    )
    endpoint.handler(api)
    return api
}


@LightningServerDsl
fun <USER: HasId<*>?, INPUT, OUTPUT> HttpEndpoint.api(
    authOptions: AuthOptions<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    belongsToInterface: Documentable.InterfaceInfo? = null,
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend AuthAndPathParts<USER, TypedServerPath0>.(INPUT)->OUTPUT
): ApiEndpoint<USER, TypedServerPath0, INPUT, OUTPUT> {
    val api = ApiEndpoint<USER, TypedServerPath0, INPUT, OUTPUT>(
        route = TypedHttpEndpoint(TypedServerPath0(path), method),
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        belongsToInterface = belongsToInterface,
        successCode = successCode,
        implementation = implementation,
    )
    handler(api)
    return api
}

@LightningServerDsl
inline fun <USER: HasId<*>?, reified INPUT, reified OUTPUT> HttpEndpoint.api(
    summary: String,
    description: String = summary,
    authOptions: AuthOptions<USER>,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    belongsToInterface: Documentable.InterfaceInfo? = null,
    successCode: HttpStatus = HttpStatus.OK,
    noinline implementation: suspend AuthAndPathParts<USER, TypedServerPath0>.(INPUT)->OUTPUT
): ApiEndpoint<USER, TypedServerPath0, INPUT, OUTPUT> {
    val api = ApiEndpoint<USER, TypedServerPath0, INPUT, OUTPUT>(
        route = TypedHttpEndpoint(TypedServerPath0(path), method),
        authOptions = authOptions,
        inputType = Serialization.module.contextualSerializerIfHandled<INPUT>(),
        outputType = Serialization.module.contextualSerializerIfHandled<OUTPUT>(),
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        belongsToInterface = belongsToInterface,
        successCode = successCode,
        implementation = implementation,
    )
    handler(api)
    return api
}
