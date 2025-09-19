package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.sdk.functionCase
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer

public fun <PATH: PathSpec, USER: HasId<*>?, INPUT, OUTPUT> explicitApiHttpHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    auth: AuthRequirement<USER>,
    successCode: HttpStatus = HttpStatus.OK,
    errorCases: List<LSError> = emptyList(),
    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT
): ApiHttpHandler<PATH, USER, INPUT, OUTPUT> =
    object : ApiHttpHandler<PATH, USER, INPUT, OUTPUT> {
        override val summary: String = summary
        override val description: String = description
        override val functionName: String = functionName
        override val inputType: KSerializer<INPUT> = inputType
        override val outputType: KSerializer<OUTPUT> = outputType
        override val auth: AuthRequirement<USER> = auth
        override val successCode: HttpStatus = successCode
        override val errorCases: List<LSError> = errorCases
        override val examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = examples

        context(server: ServerRuntime)
        override suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT = access.implementation(input)
    }

public inline fun <PATH: PathSpec, USER: HasId<*>?, reified INPUT, reified OUTPUT> ApiHttpHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    auth: AuthRequirement<USER>,
    successCode: HttpStatus = HttpStatus.OK,
    errorCases: List<LSError> = emptyList(),
    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    noinline implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT
): ApiHttpHandler<PATH, USER, INPUT, OUTPUT> =
    explicitApiHttpHandler(summary, description, functionName, serializerOrContextual<INPUT>(), serializerOrContextual<OUTPUT>(), auth, successCode, errorCases, examples, implementation)

context(server: ServerRuntime, access: HttpAccess<PATH, out USER>)
public suspend operator fun <PATH: PathSpec, USER: HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.invoke(input: INPUT): OUTPUT {
    val newAccess = access.request.access(this.auth)
    return handle(newAccess, input)
}

/**
 * Calls the handler using auth taken from the provided [request]. Access is created by [Request.access].
 * */
context(server: ServerRuntime)
public suspend fun <PATH: PathSpec, USER: HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.handle(
    request: HttpRequest<PATH>,
    input: INPUT
): OUTPUT = handle(request.access(auth), input)