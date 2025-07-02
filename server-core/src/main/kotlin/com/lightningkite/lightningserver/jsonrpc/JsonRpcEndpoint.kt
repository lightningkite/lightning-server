package com.lightningkite.lightningserver.jsonrpc

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authChecked
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.parse
import com.lightningkite.lightningserver.serialization.toHttpContent
import com.lightningkite.lightningserver.typed.AuthAccessor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

/**
 * A class that implements a JSON-RPC 2.0 endpoint.
 * This endpoint handles JSON-RPC requests and dispatches them to registered method handlers.
 *
 * @param path The server path where this JSON-RPC endpoint will be available
 * @param authOptions Authentication options for this endpoint
 */
class JsonRpcEndpoint<USER : HasId<*>?>(
    path: ServerPath,
    private val authOptions: AuthOptions<USER> = noAuth as AuthOptions<USER>
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val methods = mutableMapOf<String, JsonRpcMethodHandler<USER, *, *>>()

    init {
        // Register the endpoint with the HTTP handler
        path.post.handler { request ->
            handleRequest(request)
        }
    }

    /**
     * Registers a method handler for a specific JSON-RPC method.
     *
     * @param methodName The name of the JSON-RPC method
     * @param paramsSerializer The serializer for the method parameters
     * @param resultSerializer The serializer for the method result
     * @param handler The function that handles the method call
     */
    fun <PARAMS, RESULT> registerMethod(
        methodName: String,
        paramsSerializer: KSerializer<PARAMS>,
        resultSerializer: KSerializer<RESULT>,
        handler: suspend AuthAccessor<USER>.(PARAMS) -> RESULT
    ) {
        methods[methodName] = JsonRpcMethodHandler(
            paramsSerializer = paramsSerializer,
            resultSerializer = resultSerializer,
            handler = handler
        )
    }

    /**
     * Registers a method handler for a specific JSON-RPC method with reified type parameters.
     *
     * @param methodName The name of the JSON-RPC method
     * @param handler The function that handles the method call
     */
    inline fun <reified PARAMS, reified RESULT> registerMethod(
        methodName: String,
        noinline handler: suspend AuthAccessor<USER>.(PARAMS) -> RESULT
    ) {
        registerMethod(
            methodName = methodName,
            paramsSerializer = Serialization.module.serializer<PARAMS>(),
            resultSerializer = Serialization.module.serializer<RESULT>(),
            handler = handler
        )
    }

    /**
     * Handles an incoming HTTP request as a JSON-RPC request.
     *
     * @param request The HTTP request
     * @return The HTTP response containing the JSON-RPC response
     */
    private suspend fun handleRequest(request: HttpRequest): HttpResponse {
        // Get authentication if available
        val auth = try {
            request.authChecked<USER>(authOptions)
        } catch (e: Exception) {
            null
        }

        // Parse the request body
        val jsonElement = try {
            request.body?.parse(JsonElement.serializer()) ?: throw BadRequestException("Request body is required")
        } catch (e: Exception) {
            return createErrorResponse(
                error = JsonRpcError.parseError(),
                id = null
            )
        }

        // Handle batch requests
        if (jsonElement is JsonArray) {
            return handleBatchRequest(Serialization.json.decodeFromJsonElement(ListSerializer(JsonRpcRequest.serializer()), jsonElement), auth, request)
        }

        // Handle single request
        if (jsonElement is JsonObject) {
            return handleSingleRequest(Serialization.json.decodeFromJsonElement(JsonRpcRequest.serializer(), jsonElement), auth, request)
        }

        // Invalid request
        return createErrorResponse(
            error = JsonRpcError.invalidRequest(JsonPrimitive("Not identified as either batch or single request")),
            id = null
        )
    }

    /**
     * Handles a batch of JSON-RPC requests.
     *
     * @param jsonArray The JSON array containing multiple requests
     * @param auth The authenticated user, if any
     * @return The HTTP response containing the JSON-RPC responses
     */
    private suspend fun handleBatchRequest(jsonArray: List<JsonRpcRequest>, auth: RequestAuth<USER & Any>?, rawRequest: HttpRequest): HttpResponse {
        if (jsonArray.isEmpty()) {
            return createErrorResponse(
                error = JsonRpcError.invalidRequest(JsonPrimitive("Batch request is empty")),
                id = null
            )
        }

        val responses = jsonArray
            .map { processRequest(it, auth, rawRequest) }
            .filter { it.id != null }

        return HttpResponse(
            body = HttpContent.Text(Json.encodeToString(
                JsonArray.serializer(),
                JsonArray(responses.map { Json.encodeToJsonElement(JsonRpcResponse.serializer(), it) })
            ), ContentType.Application.Json),
            status = HttpStatus.OK
        )
    }

    /**
     * Handles a single JSON-RPC request.
     *
     * @param jsonObject The JSON object containing the request
     * @param auth The authenticated user, if any
     * @return The HTTP response containing the JSON-RPC response
     */
    private suspend fun handleSingleRequest(jsonObject: JsonRpcRequest, auth: RequestAuth<USER & Any>?, rawRequest: HttpRequest): HttpResponse {
        val response = processRequest(jsonObject, auth, rawRequest)

        // If this is a notification (no id), return an empty response
        if (jsonObject.id == null) {
            return HttpResponse(
                status = HttpStatus.NoContent
            )
        }

        return HttpResponse(
            body = HttpContent.Text(Json.encodeToString(
                JsonRpcResponse.serializer(),
                response
            ), ContentType.Application.Json),
            status = HttpStatus.OK
        )
    }

    /**
     * Processes a JSON-RPC request and returns a response.
     *
     * @param jsonObject The JSON object containing the request
     * @param auth The authenticated user, if any
     * @return The JSON-RPC response
     */
    private suspend fun processRequest(jsonObject: JsonRpcRequest, auth: RequestAuth<USER & Any>?, rawRequest: HttpRequest?): JsonRpcResponse {
        val id = jsonObject.id

        // Validate JSON-RPC version
        val jsonrpc = jsonObject.jsonrpc
        if (jsonrpc != "2.0") {
            return JsonRpcResponse(
                error = JsonRpcError.invalidRequest(JsonPrimitive("Version not supported")),
                id = id
            )
        }

        // Find method handler
        val methodHandler = methods[jsonObject.method]
        if (methodHandler == null) {
            return JsonRpcResponse(
                error = JsonRpcError.methodNotFound(),
                id = id
            )
        }

        // Get params

        // Execute method
        return try {
            val result = methodHandler.execute(auth, jsonObject.params, rawRequest)
            JsonRpcResponse(
                result = result,
                id = id
            )
        } catch (e: Exception) {
            logger.error("Error executing JSON-RPC method ${jsonObject.method}", e)
            JsonRpcResponse(
                error = JsonRpcError.internalError(),
                id = id
            )
        }
    }

    /**
     * Creates an HTTP response containing a JSON-RPC error.
     *
     * @param error The JSON-RPC error
     * @param id The request ID, if any
     * @return The HTTP response
     */
    private suspend fun createErrorResponse(error: JsonRpcError, id: String?): HttpResponse {
        return HttpResponse(
            body = HttpContent.Text(Json.encodeToString(
                JsonRpcResponse.serializer(),
                JsonRpcResponse(
                    error = error,
                    id = id
                )
            ), ContentType.Application.Json),
            status = HttpStatus.OK
        )
    }
}

/**
 * A class that handles a specific JSON-RPC method.
 *
 * @param paramsSerializer The serializer for the method parameters
 * @param resultSerializer The serializer for the method result
 * @param handler The function that handles the method call
 */
private class JsonRpcMethodHandler<USER : HasId<*>?, PARAMS, RESULT>(
    private val paramsSerializer: KSerializer<PARAMS>,
    private val resultSerializer: KSerializer<RESULT>,
    private val handler: suspend AuthAccessor<USER>.(PARAMS) -> RESULT
) {
    /**
     * Executes the method handler with the given parameters.
     *
     * @param auth The authenticated user, if any
     * @param params The method parameters as a JSON element
     * @return The method result as a JSON element
     */
    suspend fun execute(auth: RequestAuth<USER & Any>?, params: JsonElement?, rawRequest: HttpRequest?): JsonElement {
        // Parse parameters
        val parsedParams = if (params != null) {
            Json.decodeFromJsonElement(paramsSerializer, params)
        } else {
            // If params is null, try to create a default instance
            try {
                // Create a default instance - this is a simple approach that works for many cases
                // but might not work for all types
                @Suppress("UNCHECKED_CAST")
                when {
                    paramsSerializer.descriptor.isNullable -> null as PARAMS
                    else -> throw BadRequestException("Parameters are required for this method")
                }
            } catch (e: Exception) {
                throw BadRequestException("Parameters are required for this method")
            }
        }

        // Execute handler
        val result = AuthAccessor(auth, rawRequest).handler(parsedParams)

        // Serialize result
        return Json.encodeToJsonElement(resultSerializer, result)
    }
}

/**
 * Extension function to create a JSON-RPC endpoint at the given path.
 *
 * @param authOptions Authentication options for this endpoint
 * @return The created JSON-RPC endpoint
 */
fun <USER : HasId<*>?> ServerPath.jsonRpc(
    authOptions: AuthOptions<USER> = noAuth as AuthOptions<USER>
): JsonRpcEndpoint<USER> {
    return JsonRpcEndpoint(this, authOptions)
}
