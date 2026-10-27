package com.lightningkite.lightningserver.typed.jsonrpc

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.assertValidOrBadRequest
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.validators
import com.lightningkite.lightningserver.typed.access
import com.lightningkite.lightningserver.serialization.notifyEncoding
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.default
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.typed.jsonrpc.JsonRpcHandler")

/**
 * HTTP handler that processes JSON-RPC 2.0 requests and routes them to the appropriate method.
 *
 * This handler:
 * - Accepts POST requests with JSON-RPC formatted bodies
 * - Validates the JSON-RPC request format
 * - Routes to the appropriate method based on the "method" field
 * - Handles authentication per-method
 * - Returns properly formatted JSON-RPC responses or errors
 *
 * Example usage:
 * ```kotlin
 * val rpcEndpoint = path.path("rpc").post bind JsonRpcHandler(
 *     methods = listOf(addMethod, subtractMethod, getUserMethod)
 * )
 * ```
 *
 * A method that needs a specific error code or HTTP status throws [JsonRpcException]. Any other exception is an
 * internal error, logged and reported without its message. A failed authentication is an HTTP 401 or 403, not a
 * JSON-RPC error, so clients can start their authentication flow.
 *
 * @param PATH The path specification type
 * @param methods List of available RPC methods
 * @param check Runs on every parsed request before its method is looked up, e.g. to enforce headers a protocol built on
 *   JSON-RPC requires; throw [JsonRpcException] to refuse the request.
 */
public class JsonRpcHandler<PATH : PathSpec>(
    private val methods: List<JsonRpcMethod<PATH, *, *, *>>,
    private val check: suspend context(ServerRuntime) (request: HttpRequest<PATH>, rpc: JsonRpcRequest) -> Unit = { _, _ -> },
) : HttpHandler<PATH> {

    private val methodMap: Map<String, JsonRpcMethod<PATH, *, *, *>> = methods.associateBy { it.name }

    @OverrideOnly
    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse {
        // Parse the JSON-RPC request
        val rpcRequest = try {
            request.body?.parse(JsonRpcRequest.serializer())
                ?: return errorResponse(null, JsonRpcError.invalidRequest("No request body"))
        } catch (e: Exception) {
            return errorResponse(null, JsonRpcError.parseError(e.message ?: "Failed to parse JSON"))
        }

        // Validate JSON-RPC version
        if (rpcRequest.jsonrpc != "2.0") {
            return errorResponse(
                rpcRequest.id,
                JsonRpcError.invalidRequest("Invalid jsonrpc version: ${rpcRequest.jsonrpc}")
            )
        }

        // by Claude - JSON-RPC notifications have no id; per MCP spec they get 202 Accepted
        val isNotification = rpcRequest.id == null || rpcRequest.id is JsonNull

        try {
            check(request, rpcRequest)
        } catch (e: JsonRpcException) {
            return errorResponse(rpcRequest.id, e.error, e.status)
        }

        // Find the method
        val method = methodMap[rpcRequest.method]
            ?: return if (isNotification) HttpResponse(status = HttpStatus.Accepted)
            else errorResponse(rpcRequest.id, JsonRpcError.methodNotFound(rpcRequest.method))

        @Suppress("UNCHECKED_CAST")
        val typedMethod = method as JsonRpcMethod<PATH, HasId<*>?, Any?, Any?>

        // Outside the try below, so a failed authentication is an HTTP 401 or 403.
        val access = request.access(typedMethod.auth)

        // Process the method invocation
        return try {

            // Parse parameters; treat null/missing params as empty object for compatibility
            // by Claude - MCP notifications like notifications/initialized send no params
            val params = try {
                when (rpcRequest.params) {
                    null, is JsonNull -> if (typedMethod.inputType.descriptor.isNullable) null else typedMethod.inputType.default()
                    else -> server.externalSerialization.jsonWithoutExplicitNulls.decodeFromJsonElement(
                        typedMethod.inputType,
                        rpcRequest.params
                    )
                }.also { server.validators.assertValidOrBadRequest(typedMethod.inputType, it) }
            } catch (e: Exception) {
                return if (isNotification) HttpResponse(status = HttpStatus.Accepted)
                else errorResponse(
                    rpcRequest.id,
                    JsonRpcError.invalidParams(e.message ?: "Failed to parse params")
                )
            }

            // Execute the method
            val (result, customHeaders) = typedMethod.handleWithCustomHeaders(access, params)
            // Claude: encoded below without the server's encoders, so it notifies the observers itself.
            server.notifyEncoding(typedMethod.outputType, result)

            // Notifications get 202 Accepted with no body per JSON-RPC/MCP spec
            if (isNotification) return HttpResponse(status = HttpStatus.Accepted)

            // Serialize result, stripping nulls for MCP/JSON-RPC compatibility
            val resultJson = server.externalSerialization.jsonWithoutExplicitNulls.encodeToJsonElement(
                typedMethod.outputType,
                result
            )

            // Return success response
            successResponse(rpcRequest.id, resultJson, customHeaders)

        } catch (e: JsonRpcException) {
            if (isNotification) return HttpResponse(status = HttpStatus.Accepted)
            errorResponse(rpcRequest.id, e.error, e.status)
        } catch (e: HttpStatusException) {
            if (isNotification) return HttpResponse(status = HttpStatus.Accepted)
            // Map HTTP exceptions to JSON-RPC errors
            errorResponse(
                rpcRequest.id,
                JsonRpcError(
                    code = JsonRpcErrorCode(-32000 - e.status.code), // Map to server error range
                    message = e.message,
                    data = JsonPrimitive(e.message)
                )
            )
        } catch (e: Exception) {
            if (isNotification) return HttpResponse(status = HttpStatus.Accepted)
            // The message can carry internals, so it's logged rather than sent.
            logger.error(e) { "JSON-RPC method " + rpcRequest.method + " failed" }
            errorResponse(rpcRequest.id, JsonRpcError.internalError())
        }
    }

    context(server: ServerRuntime)
    private fun successResponse(
        id: JsonElement?,
        result: JsonElement,
        customHeaders: HttpHeaders = HttpHeaders.EMPTY,
    ): HttpResponse {
        val response = JsonRpcResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
        return HttpResponse(
            status = HttpStatus.OK,
            headers = customHeaders,
            body = TypedData.text(
                server.externalSerialization.jsonWithoutExplicitNulls.encodeToString(response),
                MediaType.Application.Json
            )
        )
    }

    context(server: ServerRuntime)
    private fun errorResponse(id: JsonElement?, error: JsonRpcError, status: HttpStatus = HttpStatus.OK): HttpResponse {
        val response = JsonRpcErrorResponse(
            jsonrpc = "2.0",
            error = error,
            id = id
        )
        return HttpResponse(
            status = status, // JSON-RPC errors are HTTP 200 unless a JsonRpcException says otherwise
            body = TypedData.text(
                server.externalSerialization.jsonWithoutExplicitNulls.encodeToString(response),
                MediaType.Application.Json
            )
        )
    }
}
