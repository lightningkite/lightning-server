package com.lightningkite.lightningserver.typed.jsonrpc

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.typed.HttpAccess
import com.lightningkite.lightningserver.typed.access
import com.lightningkite.lightningserver.typed.validateOrThrow
import com.lightningkite.lightningserver.typed.validators
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.HasId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

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
 * @param PATH The path specification type
 * @param methods List of available RPC methods
 */
public class JsonRpcHandler<PATH : PathSpec>(
    private val methods: List<JsonRpcMethod<PATH, *, *, *>>
) : HttpHandler<PATH> {

    private val methodMap: Map<String, JsonRpcMethod<PATH, *, *, *>> = methods.associateBy { it.name }

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

        // Find the method
        val method = methodMap[rpcRequest.method]
            ?: return errorResponse(rpcRequest.id, JsonRpcError.methodNotFound(rpcRequest.method))

        // Process the method invocation
        return try {
            @Suppress("UNCHECKED_CAST")
            val typedMethod = method as JsonRpcMethod<PATH, HasId<*>?, Any?, Any?>

            // Parse parameters
            val params = try {
                val paramsJson = rpcRequest.params ?: JsonNull
                Json.decodeFromJsonElement(typedMethod.inputType, paramsJson)
            } catch (e: Exception) {
                return errorResponse(
                    rpcRequest.id,
                    JsonRpcError.invalidParams(e.message ?: "Failed to parse params")
                )
            }

            // Validate parameters using the validators framework
            server.validators.validateOrThrow(typedMethod.inputType, params)

            // Get authenticated access
            val access = request.access(typedMethod.auth)

            // Execute the method
            val result = typedMethod.handle(access, params)

            // Serialize result
            val resultJson = Json.encodeToJsonElement(typedMethod.outputType, result)

            // Return success response
            successResponse(rpcRequest.id, resultJson)

        } catch (e: HttpStatusException) {
            // Map HTTP exceptions to JSON-RPC errors
            errorResponse(
                rpcRequest.id,
                JsonRpcError(
                    code = -32000 - e.status.code, // Map to server error range
                    message = e.message,
                    data = JsonPrimitive(e.message)
                )
            )
        } catch (e: Exception) {
            // Generic internal error
            errorResponse(
                rpcRequest.id,
                JsonRpcError.internalError(e.message ?: "Unknown error")
            )
        }
    }

    private fun successResponse(id: JsonElement?, result: JsonElement): HttpResponse {
        val response = JsonRpcResponse(
            jsonrpc = "2.0",
            result = result,
            id = id
        )
        return HttpResponse(
            status = HttpStatus.OK,
            body = TypedData.text(
                Json.encodeToString(response),
                MediaType.Application.Json
            )
        )
    }

    private fun errorResponse(id: JsonElement?, error: JsonRpcError): HttpResponse {
        val response = JsonRpcErrorResponse(
            jsonrpc = "2.0",
            error = error,
            id = id
        )
        return HttpResponse(
            status = HttpStatus.OK, // JSON-RPC errors are still HTTP 200
            body = TypedData.text(
                Json.encodeToString(response),
                MediaType.Application.Json
            )
        )
    }
}
