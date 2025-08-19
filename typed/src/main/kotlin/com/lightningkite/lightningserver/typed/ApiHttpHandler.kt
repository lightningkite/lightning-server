package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer

public interface ApiHttpHandler<PATH: PathSpec, USER: HasId<ID>?, ID : Comparable<ID>, INPUT, OUTPUT> : HttpHandler<PATH> {
    public val authOptions: AuthOptions<USER, ID>
    public val inputType: KSerializer<INPUT>
    public val outputType: KSerializer<OUTPUT>
    public val summary: String
    public val description: String
    public val successCode: HttpStatus
    public val errorCases: List<LSError>
    public val examples: List<ApiExample<INPUT, OUTPUT>>

    context(server: ServerRuntime)
    public suspend fun HttpRequestAndAuth<PATH, USER, ID>.handle(input: INPUT): OUTPUT

    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse {
        val auth = authOptions.assert(request[Authentication.CacheKey])

        @Suppress("UNCHECKED_CAST")
        val input: INPUT = when (request.method) {
            HttpMethod.GET, HttpMethod.HEAD -> request.queryParameters(inputType)
            else ->
                if (inputType == Unit.serializer()) Unit as INPUT
                else request.body?.parse(inputType) ?: throw BadRequestException("No request body provided")
        }

        server.validators.validateOrThrow(inputType, input)

        val result = HttpRequestAndAuth<PATH, USER, ID>(request, auth).handle(input)

        return HttpResponse(
            body = result.toHttpContent(request.headers.accept, outputType),
            status = successCode
        )
    }
}

context(server: ServerRuntime)
public fun <T> HttpRequest<*>.queryParameters(serializer: KSerializer<T>): T {
    try {
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return server.internalSerialization.formDataFormat.decodeFromMap(
            serializer,
            queryParameters.groupBy { it.first }.mapValues { it.value.joinToString(",") { it.second } }
        )
    } catch (e: SerializationException) {
        throw BadRequestException(
            detail = "serialization",
            message = e.message ?: "Unknown serialization error",
            cause = e.cause
        )
    }
}


public data class ApiExample<INPUT, OUTPUT>(
    val input: INPUT,
    val output: OUTPUT,
    val name: String = "Example",
    val notes: String? = null,
)
