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
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

public interface ApiHttpHandler<PATH: PathSpec, USER: HasId<ID>?, ID : Comparable<ID>, INPUT, OUTPUT> : HttpHandler<PATH> {
    public val authOptions: AuthOptions<USER, ID>
    public val inputType: KSerializer<INPUT>
    public val outputType: KSerializer<OUTPUT>
    public val summary: String
    public val description: String
    public val successCode: HttpStatus
    public val errorCases: List<LSError>
    public val examples: List<Example<INPUT, OUTPUT>>

    context(server: ServerRuntime)
    public suspend fun handle(access: HttpAccess<PATH, USER, ID>, input: INPUT): OUTPUT

    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse {
        @Suppress("UNCHECKED_CAST")
        val input: INPUT = when (request.method) {
            HttpMethod.GET, HttpMethod.HEAD -> request.queryParameters(inputType)
            else ->
                if (inputType == Unit.serializer()) Unit as INPUT
                else request.body?.parse(inputType) ?: throw BadRequestException("No request body provided")
        }

        server.validators.validateOrThrow(inputType, input)

        val result = handle(request.access(authOptions), input)

        return HttpResponse(
            body = result.toHttpContent(request.headers.accept, outputType),
            status = successCode
        )
    }

    public data class Example<INPUT, OUTPUT>(
        val input: INPUT,
        val output: OUTPUT,
        val name: String = "Example",
        val notes: String? = null,
    )
}
