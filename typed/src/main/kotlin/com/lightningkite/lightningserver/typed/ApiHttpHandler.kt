package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.queryParameters
import com.lightningkite.lightningserver.serialization.toTypedData
import com.lightningkite.lightningserver.typed.sdk.SDK
import com.lightningkite.lightningserver.typed.sdk.functionCase
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

public interface ApiHttpHandler<PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> : HttpHandler<PATH>, SDK.Documentable {
    override val auth: AuthRequirement<USER>

    override val inputType: KSerializer<INPUT>
    override val outputType: KSerializer<OUTPUT>

    public val successCode: HttpStatus
    public val errorCases: List<LSError>
    public val examples: List<Example<INPUT, OUTPUT>>

    context(server: ServerRuntime)
    public suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT

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

        val result = handle(request.access(auth), input)

        return HttpResponse(
            body = if (result == Unit) null else result.toTypedData(request.headers.accept, outputType),
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
