package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.typed.sdk.functionCase
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer

/**
 * Internal data class implementation of [ApiHttpHandler].
 *
 * Stores all endpoint metadata and delegates to the provided implementation lambda.
 * Users typically don't interact with this class directly; use [ApiHttpHandler] or
 * [explicitApiHttpHandler] factory functions instead.
 */
private data class ApiHttpHandlerData<PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT>(
    override val summary: String,
    override val description: String = "",
    override val functionName: String = summary.functionCase(),
    override val inputType: KSerializer<INPUT>,
    override val outputType: KSerializer<OUTPUT>,
    override val auth: AuthRequirement<USER>,
    override val successCode: HttpStatus = HttpStatus.OK,
    override val errorCases: List<LSError> = emptyList(),
    override val examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    val implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT,
) : ApiHttpHandler<PATH, USER, INPUT, OUTPUT> {
    context(server: ServerRuntime)
    override suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT = access.implementation(input)
}

/**
 * Creates an [ApiHttpHandler] with explicit serializers.
 *
 * Use this when you need to provide custom serializers or when reified type parameters aren't available.
 * For most cases, prefer the reified [ApiHttpHandler] function.
 *
 * @param summary Short one-line description of what the endpoint does
 * @param description Detailed description with examples and usage notes
 * @param functionName Name to use for generated client SDK methods (defaults to camelCase of summary)
 * @param inputType Serializer for the request input type
 * @param outputType Serializer for the response output type
 * @param auth Authentication requirements
 * @param successCode HTTP status code for successful responses (default: 200 OK)
 * @param errorCases List of documented error conditions
 * @param examples Example request/response pairs for documentation
 * @param implementation Business logic handler receiving authenticated access and parsed input
 * @return A configured API endpoint handler
 */
public fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> explicitApiHttpHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    auth: AuthRequirement<USER>,
    successCode: HttpStatus = HttpStatus.OK,
    errorCases: List<LSError> = emptyList(),
    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT,
): ApiHttpHandler<PATH, USER, INPUT, OUTPUT> =
    ApiHttpHandlerData(
        summary,
        description,
        functionName,
        inputType,
        outputType,
        auth,
        successCode,
        errorCases,
        examples,
        implementation
    )

/**
 * Creates an [ApiHttpHandler] with reified type parameters for automatic serializer resolution.
 *
 * This is the recommended way to create typed API endpoints. Serializers are automatically
 * resolved from the type parameters.
 *
 * Example:
 * ```kotlin
 * val getUser = path.path("users").arg<String>("id").get bind ApiHttpHandler<_, User?, String, User>(
 *     summary = "Get User",
 *     description = "Retrieves a user by their ID",
 *     auth = authOptions<User>(),
 *     errorCases = listOf(LSError(404, "not-found", "User not found")),
 *     implementation = { userId ->
 *         database().users.get(userId) ?: throw NotFoundException()
 *     }
 * )
 * ```
 *
 * @param INPUT Request input type (must be serializable)
 * @param OUTPUT Response output type (must be serializable)
 * @param summary Short one-line description
 * @param description Detailed description
 * @param functionName SDK method name (defaults to camelCase of summary)
 * @param auth Authentication requirements
 * @param successCode HTTP status for success (default: 200 OK)
 * @param errorCases Documented error conditions
 * @param examples Request/response examples
 * @param implementation Business logic handler
 * @return A configured API endpoint handler
 */
public inline fun <PATH : PathSpec, USER : HasId<*>?, reified INPUT, reified OUTPUT> ApiHttpHandler(
    summary: String,
    description: String = "",
    functionName: String = summary.functionCase(),
    auth: AuthRequirement<USER>,
    successCode: HttpStatus = HttpStatus.OK,
    errorCases: List<LSError> = emptyList(),
    examples: List<ApiHttpHandler.Example<INPUT, OUTPUT>> = emptyList(),
    noinline implementation: suspend context(ServerRuntime) HttpAccess<PATH, USER>.(INPUT) -> OUTPUT,
): ApiHttpHandler<PATH, USER, INPUT, OUTPUT> =
    explicitApiHttpHandler(
        summary,
        description,
        functionName,
        serializerOrContextual<INPUT>(),
        serializerOrContextual<OUTPUT>(),
        auth,
        successCode,
        errorCases,
        examples,
        implementation
    )

/**
 * Invokes an API endpoint internally from within another endpoint handler.
 *
 * This allows server-side code to call typed endpoints directly, reusing the same
 * business logic and validation. The endpoint is re-authenticated with its own auth requirements.
 *
 * @param input The request input
 * @return The endpoint's output
 */
context(server: ServerRuntime, access: HttpAccess<PATH, out USER>)
public suspend operator fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.invoke(
    input: INPUT,
): OUTPUT {
    val newAccess = access.request.access(auth)
    return handle(newAccess, input)
}

/**
 * Invokes a PathSpec0 endpoint (no path parameters) from server-side code.
 *
 * @param request HTTP request for context
 * @param input Request input
 * @return Endpoint output
 */
context(server: ServerRuntime)
public suspend operator fun <USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PathSpec0, USER, INPUT, OUTPUT>.invoke(
    request: HttpRequest<*>,
    input: INPUT,
): OUTPUT = handle(
    request.copyWithNewPathType(RawHttpEndpoint(location.path, location.method)).access(auth),
    input
)

/**
 * Invokes a PathSpec1 endpoint (one path parameter) from server-side code.
 *
 * @param request HTTP request for context
 * @param first First path parameter value
 * @param input Request input
 * @return Endpoint output
 */
context(server: ServerRuntime)
public suspend operator fun <A, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PathSpec1<A>, USER, INPUT, OUTPUT>.invoke(
    request: HttpRequest<*>,
    first: A,
    input: INPUT,
): OUTPUT = handle(
    request.copyWithNewPathType(RawHttpEndpoint(location.path, first, location.method)).access(auth),
    input
)

/**
 * Invokes a PathSpec2 endpoint (two path parameters) from server-side code.
 *
 * @param request HTTP request for context
 * @param first First path parameter value
 * @param second Second path parameter value
 * @param input Request input
 * @return Endpoint output
 */
context(server: ServerRuntime)
public suspend operator fun <A, B, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PathSpec2<A, B>, USER, INPUT, OUTPUT>.invoke(
    request: HttpRequest<*>,
    first: A,
    second: B,
    input: INPUT,
): OUTPUT = handle(
    request.copyWithNewPathType(RawHttpEndpoint(location.path, first, second, location.method)).access(auth),
    input
)

/**
 * Invokes a PathSpec3 endpoint (three path parameters) from server-side code.
 *
 * @param request HTTP request for context
 * @param first First path parameter value
 * @param second Second path parameter value
 * @param third Third path parameter value
 * @param input Request input
 * @return Endpoint output
 */
context(server: ServerRuntime)
public suspend operator fun <A, B, C, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PathSpec3<A, B, C>, USER, INPUT, OUTPUT>.invoke(
    request: HttpRequest<*>,
    first: A,
    second: B,
    third: C,
    input: INPUT,
): OUTPUT = handle(
    request.copyWithNewPathType(RawHttpEndpoint(location.path, first, second, third, location.method)).access(auth),
    input
)