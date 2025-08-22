package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer

public fun <PATH: PathSpec, USER: HasId<ID>?, ID : Comparable<ID>, INPUT, OUTPUT> ApiHttpHandler(
    summary: String,
    description: String = "",
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    authOptions: AuthOptions<USER, ID>,
    successCode: HttpStatus = HttpStatus.OK,
    errorCases: List<LSError> = emptyList(),
    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    handler: suspend context(ServerRuntime) HttpAccess<PATH, USER, ID>.(INPUT) -> OUTPUT
): ApiHttpHandler<PATH, USER, ID, INPUT, OUTPUT> =
    object : ApiHttpHandler<PATH, USER, ID, INPUT, OUTPUT> {
        override val summary: String = summary
        override val description: String = description
        override val inputType: KSerializer<INPUT> = inputType
        override val outputType: KSerializer<OUTPUT> = outputType
        override val authOptions: AuthOptions<USER, ID> = authOptions
        override val successCode: HttpStatus = successCode
        override val errorCases: List<LSError> = errorCases
        override val examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = examples

        context(server: ServerRuntime)
        override suspend fun HttpAccess<PATH, USER, ID>.handle(input: INPUT): OUTPUT = handler(input)
    }

public inline fun <PATH: PathSpec, USER: HasId<ID>?, ID : Comparable<ID>, reified INPUT, reified OUTPUT> ApiHttpHandler(
    summary: String,
    description: String = "",
    authOptions: AuthOptions<USER, ID>,
    successCode: HttpStatus = HttpStatus.OK,
    errorCases: List<LSError> = emptyList(),
    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    noinline handler: suspend context(ServerRuntime) HttpAccess<PATH, USER, ID>.(INPUT) -> OUTPUT
): ApiHttpHandler<PATH, USER, ID, INPUT, OUTPUT> =
    ApiHttpHandler(summary, description, serializerOrContextual<INPUT>(), serializerOrContextual<OUTPUT>(), authOptions, successCode, errorCases, examples, handler)