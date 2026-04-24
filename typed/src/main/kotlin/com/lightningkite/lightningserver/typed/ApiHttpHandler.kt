package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.sdk.SDK
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Typed HTTP endpoint handler with automatic serialization, validation, and documentation.
 *
 * This interface extends [HttpHandler] to provide type-safe request handling with:
 * - Automatic input parsing from query parameters (GET) or request body (POST/PUT/PATCH)
 * - Input validation via the validators framework
 * - Authentication and authorization via [AuthRequirement]
 * - Automatic output serialization with content negotiation
 * - SDK generation support via [SDK.Documentable]
 *
 * Implementations should focus on business logic in [handle], while infrastructure concerns
 * (parsing, validation, serialization) are handled automatically.
 *
 * @param PATH The path specification type defining URL parameters
 * @param USER The authenticated user type (or null for unauthenticated endpoints)
 * @param INPUT The request input type
 * @param OUTPUT The response output type
 */
public interface ApiHttpHandler<PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> : HttpHandler<PATH>,
    SDK.Documentable {
    /**
     * Authentication requirements for this endpoint.
     */
    override val auth: AuthRequirement<USER>

    /**
     * Serializer for the input type.
     */
    override val inputType: KSerializer<INPUT>

    /**
     * Serializer for the output type.
     */
    override val outputType: KSerializer<OUTPUT>

    /**
     * HTTP status code to return on success (typically 200 OK, 201 Created, or 204 No Content).
     */
    public val successCode: HttpStatus

    /**
     * List of possible error cases this endpoint may return.
     * Used for API documentation and client SDK generation.
     */
    public val errorCases: List<LSError>

    /**
     * Example request/response pairs for documentation and testing.
     */
    public val examples: List<Example<INPUT, OUTPUT>>

    /**
     * Business logic for handling the request with typed input and output.
     *
     * This is the main method to implement. Input parsing, validation, authentication,
     * and output serialization are handled automatically.
     *
     * @param access Authenticated access object with request context and user
     * @param input Parsed and validated input
     * @return Response output to be serialized
     */
    context(server: ServerRuntime)
    public suspend fun handle(access: HttpAccess<PATH, USER>, input: INPUT): OUTPUT

    /**
     * Internal implementation that orchestrates parsing, validation, handling, and serialization.
     *
     * This method:
     * 1. Parses input from query params (GET/HEAD) or body (other methods)
     * 2. Validates input using the validators framework
     * 3. Calls [handle] with the parsed input
     * 4. Serializes output based on Accept header content negotiation
     *
     * GOTCHA: For GET/HEAD requests, input is parsed from query parameters, which means
     * complex objects should be avoided. For POST/PUT/PATCH, input comes from request body.
     */
    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse {
        @Suppress("UNCHECKED_CAST")
        val input: INPUT = when (request.path.method) {
            HttpMethod.GET, HttpMethod.HEAD -> request.queryParameters(inputType)
            else ->
                if (inputType == Unit.serializer()) Unit as INPUT
                else request.body?.parse(inputType) ?: throw BadRequestException("No request body provided")
        }

        server.validators.assertValidOrBadRequest(inputType, input)

        val result = handle(request.access(auth), input)

        return HttpResponse(
            body = if (result == Unit) null else result.toTypedData(request.headers.accept, outputType),
            status = successCode,
            headers = HttpHeaders {
                add(HttpHeader.Vary, HttpHeader.Accept)
            }
        )
    }

    /**
     * Example request/response pair for documentation and testing.
     *
     * @param INPUT Request input type
     * @param OUTPUT Response output type
     * @property input Example input value
     * @property output Expected output value for this input
     * @property name Human-readable name for this example
     * @property notes Optional explanatory notes about this example
     */
    public data class Example<INPUT, OUTPUT>(
        val input: INPUT,
        val output: OUTPUT,
        val name: String = "Example",
        val notes: String? = null,
    )
}

/*
 * TODO: API Improvements
 *
 * 1. Consider adding rate limiting support at the endpoint level (annotations or configuration)
 * 2. Add request/response logging hooks for debugging and monitoring
 * 3. Consider providing a way to specify cache headers (ETag, Last-Modified, Cache-Control)
 * 4. Add support for conditional requests (If-None-Match, If-Modified-Since)
 * 5. Consider adding built-in support for request tracing (distributed tracing IDs)
 * 6. The errorCases list could be enhanced with response examples for each error
 * 7. Add deprecation support (@Deprecated equivalent for endpoints in SDK generation)
 * 8. Consider adding request/response schema validation levels (strict vs lenient)
 */
