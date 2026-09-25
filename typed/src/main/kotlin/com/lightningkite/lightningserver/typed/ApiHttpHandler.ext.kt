package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.toLSError
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.serialization.assertValidOrBadRequest
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.toTypedData
import com.lightningkite.lightningserver.serialization.validators
import com.lightningkite.lightningserver.typedoutput.emitTypedOutput
import com.lightningkite.services.database.HasId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.builtins.serializer

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.typed.ApiHttpHandler")

/**
 * Runs this endpoint for [request] as a new execution caused by the current one, through the server's
 * HTTP interceptors, using [input] as-is rather than parsing it from the request.
 *
 * Authentication is resolved from the request the interceptors pass on, as it is for a routed request.
 */
context(server: ServerRuntime)
public suspend fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.handleWithMetrics(
    request: HttpRequest<PATH>,
    input: INPUT,
): OUTPUT {
    // The interceptor chain only speaks HttpResponse, so the typed result is carried out beside it.
    var outcome: Result<ApiHttpHandlerOutput<OUTPUT>>? = null

    @OptIn(InternalLightningServerApi::class)
    val httpResponse = executeWithMetrics(request) { req ->
        val result = runCatching { handleTypedInput(req.access(auth), input) }
        outcome = result
        result.getOrThrow().processed
    }

    // Rethrow the endpoint's own exception so callers can catch it by type. A timeout is excluded: it is
    // already reported by the response, and rethrowing a CancellationException would read as the caller
    // itself being cancelled.
    outcome?.exceptionOrNull()?.let {
        if (it is CancellationException) currentCoroutineContext().ensureActive()
        else throw it
    }
    if (!httpResponse.status.success) throw HttpStatusException(httpResponse.toLSError())
    outcome?.let { return it.getOrThrow().output }

    // An interceptor answered before the endpoint ran, so its response is the only output there is.
    @Suppress("UNCHECKED_CAST")
    if (outputType == Unit.serializer()) return Unit as OUTPUT
    return httpResponse.body?.parse(outputType)
        ?: throw IllegalStateException("An interceptor answered '${request.path}' without a body to read the output from.")
}


/**
 * Invokes an API endpoint internally from within another endpoint handler.
 *
 * Runs as its own execution through the server's HTTP interceptors, and is re-authenticated with the
 * endpoint's own auth requirements.
 *
 * @param input The request input
 * @return The endpoint's output
 */
context(server: ServerRuntime, access: HttpAccess<PATH, out USER>)
public suspend operator fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.invoke(
    input: INPUT,
): OUTPUT = handleWithMetrics(access.request.internalCallTo(access.request.path), input)

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
): OUTPUT = handleWithMetrics(
    request.internalCallTo(RawHttpEndpoint(location.path, location.method)),
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
): OUTPUT = handleWithMetrics(
    request.internalCallTo(RawHttpEndpoint(location.path, first, location.method)),
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
): OUTPUT = handleWithMetrics(
    request.internalCallTo(RawHttpEndpoint(location.path, first, second, location.method)),
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
): OUTPUT = handleWithMetrics(
    request.internalCallTo(RawHttpEndpoint(location.path, first, second, third, location.method)),
    input
)



// INTERNAL IMPLEMENTATION STUFF

// Drops the caller's Accept header so the called endpoint encodes its output in its own default format:
// the caller may accept something this output can't be encoded as.
private fun <PATH : PathSpec> HttpRequest<*>.internalCallTo(path: RawHttpEndpoint<PATH>): HttpRequest<PATH> =
    subRequest(path, headers = headers.copy { remove(HttpHeader.Accept) })

internal data class ApiHttpHandlerOutput<OUTPUT>(
    val output: OUTPUT,
    val processed: HttpResponse
)

/**
 * Everything a request to this endpoint does once [input] is parsed: validation, the handler itself,
 * typed output observation, and encoding the response.
 */
context(server: ServerRuntime)
internal suspend fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.handleTypedInput(
    access: HttpAccess<PATH, USER>,
    input: INPUT,
): ApiHttpHandlerOutput<OUTPUT> {
    server.validators.assertValidOrBadRequest(inputType, input)

    val result = try {
        handle(access, input)
    } catch (e: HttpStatusException) {
        // Warn the user if the thrown exception isn't listed in the error cases
        if (e.detail.isNotBlank() && errorCases.none { it.detail == e.detail && it.http == e.status.code }) {
            logger.warn {
                "Endpoint threw HttpStatusException(status=${e.status.code}, detail=\"${e.detail}\") " +
                        "not present in its declared errorCases ${errorCases.map { "${it.http}:${it.detail}" }}. " +
                        "Add it to errorCases or align the thrown detail so clients/docs stay accurate."
            }
        }
        throw e
    }

    emitTypedOutput(access.request, outputType, result)

    return ApiHttpHandlerOutput(
        result,
        HttpResponse(
            body = if (result == Unit) null else result.toTypedData(access.request.headers.accept, outputType),
            status = successCode,
            headers = HttpHeaders {
                add(HttpHeader.Vary, HttpHeader.Accept)
            }
        )
    )
}
