package com.lightningkite.lightningserver.typed.rpc

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.rpc.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.access
import com.lightningkite.lightningserver.typed.validators
import com.lightningkite.lightningserver.typed.validateOrThrow
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/**
 * Handles JSON-RPC 2.0 requests by routing to existing ApiHttpHandlers.
 * Supports both single requests and batch requests.
 */
public class JsonRpcHandler<PATH : PathSpec>(
    private val methodRegistry: Map<String, ApiHttpHandler<PATH, *, *, *>>
) : HttpHandler<PATH> {

    context(server: ServerRuntime)
    override suspend fun handle(request: HttpRequest<PATH>): HttpResponse {
        val json = server.externalSerialization.json

        return try {
            val bodyText = request.body?.text() ?: throw RpcException(
                code = RpcErrorCode.INVALID_REQUEST,
                msg = "Request body is required"
            )

            // Check if it's a batch request (array) or single request (object)
            val jsonElement = json.parseToJsonElement(bodyText)

            val responseBody = when (jsonElement) {
                is JsonArray -> {
                    // Batch request
                    val responses = jsonElement.map { element ->
                        processRequest(json, request, element)
                    }
                    json.encodeToString(ListSerializer(JsonElement.serializer()), responses)
                }
                is JsonObject -> {
                    // Single request
                    val response = processRequest(json, request, jsonElement)
                    json.encodeToString(JsonElement.serializer(), response)
                }
                else -> throw RpcException(
                    code = RpcErrorCode.INVALID_REQUEST,
                    msg = "Request must be a JSON object or array"
                )
            }

            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text(responseBody, MediaType.Application.Json),
                headers = HttpHeaders {
                    set(HttpHeader.ContentType, MediaType.Application.Json.toString())
                }
            )
        } catch (e: RpcException) {
            // Protocol-level error
            HttpResponse(
                status = HttpStatus.OK, // JSON-RPC always returns 200
                body = TypedData.text(
                    json.encodeToString(RpcErrorResponse.serializer(), e.toResponse()),
                    MediaType.Application.Json
                )
            )
        } catch (e: Exception) {
            // Unexpected error
            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text(
                    json.encodeToString(
                        RpcErrorResponse.serializer(),
                        RpcErrorResponse(
                            error = RpcError(
                                code = RpcErrorCode.INTERNAL_ERROR,
                                message = "Internal error: ${e.message}"
                            ),
                            id = null
                        )
                    ),
                    MediaType.Application.Json
                )
            )
        }
    }

    context(server: ServerRuntime)
    private suspend fun processRequest(
        json: Json,
        request: HttpRequest<PATH>,
        element: JsonElement
    ): JsonElement {
        return try {
            // Parse the request envelope
            val envelope = json.decodeFromJsonElement(RpcRequestEnvelope.serializer(), element)

            // Process and return response
            val response = handleRequest(json, request, envelope)
            json.encodeToJsonElement(RpcResponse.serializer(), response)
        } catch (e: RpcException) {
            // RPC protocol error
            json.encodeToJsonElement(RpcErrorResponse.serializer(), e.toResponse())
        } catch (e: HttpStatusException) {
            // Application error
            json.encodeToJsonElement(RpcErrorResponse.serializer(), e.toRpcError(json, null))
        } catch (e: Exception) {
            // Unexpected error
            json.encodeToJsonElement(
                RpcErrorResponse.serializer(),
                RpcErrorResponse(
                    error = RpcError(
                        code = RpcErrorCode.INTERNAL_ERROR,
                        message = "Internal error: ${e.message}"
                    ),
                    id = null
                )
            )
        }
    }

    context(server: ServerRuntime)
    private suspend fun handleRequest(
        json: Json,
        request: HttpRequest<PATH>,
        envelope: RpcRequestEnvelope
    ): RpcResponse {
        // Find handler by method name
        val handler = methodRegistry[envelope.method]
            ?: throw RpcException(
                code = RpcErrorCode.METHOD_NOT_FOUND,
                msg = "Method not found: ${envelope.method}",
                requestId = envelope.id
            )

        // Deserialize params using handler's input type
        val params = envelope.params
        val input: Any? = try {
            when {
                params == null || params is JsonNull -> {
                    // No params provided
                    if (handler.inputType.descriptor.serialName == "kotlin.Unit") {
                        Unit
                    } else {
                        throw RpcException(
                            code = RpcErrorCode.INVALID_PARAMS,
                            msg = "Method requires parameters",
                            requestId = envelope.id
                        )
                    }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    json.decodeFromJsonElement(
                        handler.inputType as KSerializer<Any?>,
                        params
                    )
                }
            }
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            throw RpcException(
                code = RpcErrorCode.INVALID_PARAMS,
                msg = "Invalid params: ${e.message}",
                requestId = envelope.id
            )
        }

        // Validate input
        @Suppress("UNCHECKED_CAST")
        server.validators.validateOrThrow(handler.inputType as SerializationStrategy<Any?>, input)

        // Execute handler with proper authentication
        val output = try {
            @Suppress("UNCHECKED_CAST")
            val typedHandler = handler as ApiHttpHandler<PATH, HasId<*>?, Any?, Any?>
            val access = request.access(typedHandler.auth)
            typedHandler.handle(access, input)
        } catch (e: HttpStatusException) {
            throw e // Will be caught and converted to RpcErrorResponse
        } catch (e: Exception) {
            throw RpcException(
                code = RpcErrorCode.INTERNAL_ERROR,
                msg = "Handler error: ${e.message}",
                requestId = envelope.id
            )
        }

        // Serialize result
        val resultJson = json.encodeToJsonElement(
            @Suppress("UNCHECKED_CAST")
            handler.outputType as SerializationStrategy<Any?>,
            output
        )

        return RpcResponse(
            result = resultJson,
            id = envelope.id
        )
    }
}

/**
 * Maps HttpStatusException to JSON-RPC error response.
 */
private fun HttpStatusException.toRpcError(json: Json, requestId: JsonElement?): RpcErrorResponse {
    val code = when (this) {
        is BadRequestException -> RpcErrorCode.INVALID_PARAMS
        is UnauthorizedException -> RpcErrorCode.UNAUTHORIZED
        is ForbiddenException -> RpcErrorCode.FORBIDDEN
        is NotFoundException -> RpcErrorCode.NOT_FOUND
        else -> RpcErrorCode.INTERNAL_ERROR
    }

    val errorData = if (detail.isNotEmpty() || data.isNotEmpty()) {
        json.encodeToJsonElement(
            buildMap {
                if (detail.isNotEmpty()) put("detail", detail)
                if (data.isNotEmpty()) put("data", data)
            }
        )
    } else {
        null
    }

    return RpcErrorResponse(
        error = RpcError(
            code = code,
            message = message,
            data = errorData
        ),
        id = requestId
    )
}
